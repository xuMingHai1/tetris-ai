# 固定回放诊断 JVM 的网络拒绝设计

状态：设计已由 [PR #172](https://github.com/xuMingHai1/tetris-ai/pull/172) 正常合并接受，仅接受后续 test-only 实现范围；本文件没有网络隔离实测、历史回放或执行许可。任务 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)，设计源码基线 `7679c6e24e10f35a786ec68caa5512c3f8d126e4`。

## 问题与已有证据

[原设计](../design/heart-fixed-witness-replay.md)允许构建下载依赖，但要求诊断 JVM 禁止网络。当前 `fixed_witness_replay_pipe.py` 直接启动 `command_for` 的普通 JVM，admission 与 finalize 均明确返回 `network_isolation_verified=false`。环境 allowlist、无 provider key、精确 argv、成功 READY/ACK 都不能证明 OS 拒绝网络。现有进程组件只监督已审查的协作子进程，并非恶意进程沙箱。

需要在 JVM 第一条指令执行前建立不可由该 JVM 放宽的拒绝机制，同时保持真实 Java PID、`/proc` argv/exe/cwd/environ 观测、stdin ACK/EOF、原进程组清理。只在 READY 后检查或仅执行一次连接失败探针不足：没有路由、服务未监听、DNS 失败也会产生连接失败。

## 比较与选择

| 方案 | 能力与代价 | 决定 |
| --- | --- | --- |
| 环境、代理禁用或 Java 层检查 | 无法约束 native socket；Java 27 无可依赖的 SecurityManager 沙箱 | 排除 |
| 单独 network namespace | 可隔离主机路由，但 loopback/socket 仍存在；user namespace 能力受 runner 策略影响 | 不作为“禁止网络”的充分条件 |
| Docker 包装 | 可有网络命名空间，但引入 PID/挂载/路径映射与容器身份认证，超出现有直接 JVM 接线 | 保留为失败后的另行设计，不能自动切换 |
| exec 前安装 seccomp socket 拒绝过滤器 | 继承到普通 JVM/线程/后代；不改 Java 命令、文件布局或 UID；需要 Linux ABI 与 libseccomp 能力核验 | 选择有限能力实现与反例验证 |

推荐仅 Linux x86-64 的 test-only Python launcher，通过 `ctypes` 加载宿主 `libseccomp`，无 pip/生产依赖、不安装软件、不请求 root、无 provider/公网请求。宿主库缺失或 ABI/能力不满足则 fail closed；不以 mock 结果认证 Linux 能力，也不自动退回无隔离 JVM。实现必须绑定库真实路径、版本和文件 SHA-256；不能只信动态库名称。这里选择的是待实现验证方案，不声称当前 runner 已具备能力。

## 启动与认证契约

1. Wrapper 构建及所需依赖获取仍在过滤器外；其存储分类继续等待 [PR #170](https://github.com/xuMingHai1/tetris-ai/pull/170)，本设计不采纳任何分类或放宽 10 MiB。父进程使用现有显式环境、绝对 deadline、同 root 输出 quota，先认证 launcher、Python/库字节与固定参数，拒绝额外 fd。stdin/stdout/stderr 是唯一继承管道，禁止传入 socket fd。
2. launcher 进入现有独立 session/process group，先设置并核验 `PR_SET_NO_NEW_PRIVS`，再安装 seccomp；任一步失败直接非零退出，不 exec Java。固定绝对 Java 路径、完整 argv 和过滤器策略 hash 由父进程绑定；不经 shell、不 fork 一个不受控 Java 孙进程。
3. 过滤器默认允许离线 JVM 所需非网络 syscall；对 `socket`、`socketpair`、`connect`、`bind`、`listen`、`accept`、`accept4`、`sendto`、`sendmsg`、`sendmmsg`、`recvfrom`、`recvmsg`、`recvmmsg`、`shutdown` 返回固定 `EPERM`。所有地址族（含 AF_UNIX、AF_INET、AF_INET6、NETLINK）一律拒绝；不依赖目的地址、端口、路由或 DNS。拒绝 `io_uring_setup`，防止借另一异步接口建立网络。策略必须显式限制为原生 x86-64 ABI，拒绝 x32/兼容 ABI；无法解析必要 syscall、无法建立 ABI 拒绝规则即停止，不静默漏项。
4. 成功安装后同 PID `execve` 原 `command_for` 的完整 Java 命令，继承过滤器与 no-new-privs。不得消费 stdin、输出伪 READY 或改变 nonce。launcher 准备耗时计入同一个 JVM 120 秒窗口；Java exec 后真实 `/proc` argv/exe 必须仍精确匹配现有认证。launcher 的启动命令与 Java 最终命令分别保存，不能让认证接受任意包装 argv。
5. 父进程在 READY 认证前后检查同 PID `/proc/<pid>/status` 的 `NoNewPrivs=1`、`Seccomp=2` 以及可用的 filter count，并绑定已审查的启动链、nonce、代码/库/策略 hash；缺失、漂移、PID 消失或不能核验即禁止 ACK。进程组清理仍是原调用方职责。launcher 不增加 artifact writer；后续记录由父 quota 写前计费，所有 stderr/stdout 继续计入现有管道额度。
6. `/proc` 的 seccomp 模式和 filter count **不披露过滤器内容**；它们单独不足以证明本策略。证据依赖已审查、字节绑定的合作式 launcher 安装链及真实内核反例测试，不是对恶意父进程、被篡改内核、JVM 内存或任意本机攻击者的认证。继承的更严格宿主过滤器亦不能冒充本过滤器已安装。实现须明确保留这项局限。

禁止在本设计 PR 或其人工验收中设置历史诊断授权。实现可在独立网络证据字段表示该组件已核验，但现有无隔离入口继续返回 false；`diagnostic_authorized` 与 `diagnostic_started` 始终 false。不得仅凭调用方 boolean、READY 声明或成功退出将原字段提升为 true。

## 冻结的人工验收与预算

实现只触及独立 `scripts/*fixed_witness*`、正常 `src/test` 人工探针/桥接和研究说明；不改 src/main、生产资源、pom/Wrapper、原 guard、selector、历史 fixture、原冻结协议或 workflow。Linux 必须真实执行；Windows 明确 skip，不计能力通过。现有普通 JVM verify-only 测试保留，再增加隔离路径验证；真实构建复用 Wrapper，不能用伪造 prepare receipt 冒充 clean-build 来源。

人工能力套件单次最多 16 个子进程、每个 native/Python 探针最多 5 秒；普通 JVM 单次仍最多 120 秒，整套含 clean build 最多 900 秒、build 最多 600 秒、JVM 父监督最多 180 秒，沿用清理预留，不为 launcher 重置时钟。全部 stdout/artifact 共用最多 10 MiB；Java 使用原 256 MiB heap/128 MiB metaspace，RSS 未观测仍报告未知。没有历史输入、动作回放、候选枚举、seed/population、外部费用或公网探针。探针可同一进程顺序检查多个 syscall，不因某项失败自动增加次数或切环境重试。

必要验收覆盖：

- 过滤器外只用本地 AF_UNIX socketpair 作阳性对照，确认宿主允许对应操作；过滤器内相同操作必须精确 EPERM。INET/INET6 socket 创建同样精确 EPERM，不实际连接公网，不把一般连接失败当作拒绝证据。
- 原生 syscall 探针覆盖上述完整拒绝表及 io_uring；非法 fd 仍须先被过滤为 EPERM，而非 EBADF。ABI 反例验证 x32/兼容入口不能绕过；预期终止的探针独立有界，异常不会污染主 Python 测试进程。
- 缺库、错误库/hash/策略、失败 no-new-privs 或 filter load、缺必要 syscall/ABI 支持均不 exec Java；人工 marker 必须不存在。注入失败测试仅证明控制流，不能替代真实内核验证。
- launcher exec 的真实普通 JVM 产生 READY，父认证观察相同 PID/精确 Java argv/exe、真实加载 class/receipt，再发原 ACK 与 EOF，最终 VERIFIED_NO_REPLAY；加载/认证失败、额外 fd、伪造或缺失 seccomp 状态、nonce/PID/argv 漂移均拒绝 ACK。
- 真实隔离子进程在线程及一个受控后代中仍拒绝 socket；filter 无法被 no-new-privs 子进程卸载或放宽。验证超时、exec 失败、leader 提前退出时原 process group 清理、quota 前缀与 cleanup reserve 不退化。测试不承诺防恶意 setsid 逃逸。

成功须全部必要人工项真实通过、最终完整 diff 独立 Review 无阻断、适用 CI 成功。Linux capability 不可用、策略漏项、离线 JVM 不兼容或预算耗尽，标记 CAPABILITY_UNAVAILABLE / IMPLEMENTATION_INVALID / INCOMPLETE 并停止该实现验收；不能删除拒绝项、开网络特例、加预算或自动改用容器来挽救。普通实现缺陷可按源码修复重新验证，但不能把失败当成历史诊断结果。

## 接受边界与接续

最终设计 Review/CI/正常合并后，仅接受上述有界实现与人工验收，不修改原诊断冻结协议。实现最终 Review/CI 后仍需完整 supervisor、root 预留与 journal/report checker、PR #170 存储决定、兼容性/input hash 刷新和唯一执行登记，才能考虑历史 22 步。#140/GoalWindow 停止、#95/#96、reserved/provider/runtime/default 权限不变。

本文件新增研究接线设计，不改变生产架构或运行方式；研究索引补链接，README/AGENTS/ADR 无需变化。本次没有实测结果。

## 实现草稿断点（2026-11-04）

基于 `24bb1770d77a1f186f5d896d469e828c21d285e4`，新增独立 stdlib/ctypes launcher，绑定 Python/launcher/libseccomp 实际路径与字节、版本、策略和完整 exec argv；原生 ABI 下完整网络 syscall 与 io_uring_setup 返回 EPERM，兼容 ABI kill，继承 fd 拒绝、no-new-privs、禁止 core dump。同 PID exec 不消费 ACK 管道。`run_verify_only(network_denial=True)` 从内部生成启动链，在原 deadline/同 root 额度内启动，READY 原认证前后另查 seccomp 状态。原无隔离路径和原 `network_isolation_verified=false`、历史权限字段均保留；新组件证据不等于完整隔离验收。

草稿包含原生 syscall、ABI、线程/受控后代、额外 fd、策略/缺库、超时/exec 失败组件测试。本次新增修改：错误库（真实文件与匹配 hash，但不是 libseccomp）、错误库 hash 均拒绝 marker；隔离 leader 提前退出后的 pipe-holding 后代必须清理；输出超限保留精确 256 字节前缀，并验证子额度耗尽不再启动、不退还父预留。独立 `component_suite()` 仅执行十二个探针 leader 和两个受控 fork，所有探针管道与 unittest 结果输出共用 64 KiB、50 秒绝对截止时间，每个探针最多 5 秒；256 字节子额度先从同一 root 永久预留，不额外增加额度。不运行构建或 JVM。原 clean-build 测试保留原行为，不新接隔离 JVM。Windows 只运行可移植拒绝逻辑，Linux 组件结果须由当前 revision CI 原始日志确认，不预记通过。

本次新增修改：launcher 的失败日志固定为 `NETWORK_LAUNCH_FAILED stage=<固定阶段> classification=<固定分类>`，exit 78，不打印异常类型、消息、traceback、路径、argv 或 nonce。INPUT/BINDING/DESCRIPTORS/FILTER_BASELINE/FILTER_OBSERVATION/EXEC 失败分类为 IMPLEMENTATION_INVALID；HOST/CORE_LIMIT/FILTER_INSTALL 失败分类为 CAPABILITY_UNAVAILABLE，描述当前启动链无法建立能力，不断言具体宿主根因。已绑定库丢失或 hash/版本变化属于 BINDING 无效，不静默重绑定。任一步失败即停止，不重试或无隔离 fallback。父进程超时/输出耗尽及 exec 成功后的 JVM 失败仍由原 supervisor 记录，不能由 launcher 冒报为历史诊断结果；INCOMPLETE 不在 launcher 内伪造。新增三项可移植人工测试覆盖非法输入、每阶段异常安全输出/后续操作不执行和 exec 单次失败；现有 Linux 缺库/错误库/hash/策略/exec 反例同时核对精确失败日志，不新增探针子进程。组件现为 17 项测试，仍十二 leader/两个 fork/同一 64 KiB 与 50 秒，不代表完整能力接受。

**尚未完成，不合并、不认证能力：** 完整人工套件的探针额度与 clean-build 额度尚未聚合为同一个累计 10 MiB；完整套件的 16 子进程计数尚未覆盖 prepare/verify 中多次 Git inventory，不能把 probe 数当作全进程计数。独立初审按现有源码复核，仅完整认证链 inventory 已需 35 个 Git 子进程，尚不含构建/探针/JVM。后续须明确并审查该计数边界，不能静默排除 inventory 或增预算；真实隔离普通 JVM、同一完整套件绝对截止时间与累计硬界仍需补齐。最终完整 revision 独立 Review 与适用 CI 仍是合并前置。PR #170 存储决定及历史诊断的全部其他前置不变。
