# 固定 witness：工具链与子进程环境组件

这是 [固定动作回放设计](../design/heart-fixed-witness-replay.md) 的分阶段实现，接续
[字节身份组件](heart-fixed-witness-replay-identity.md)。只有该组件本身的最终 Review、
CI 与正常合并满足后才接受；它不登记 session，也不启动构建或历史 22 步回放。

## 本次行为

- `scripts/fixed_witness_replay_environment.py` 的 `toolchain_identity` 登记整个指定 JDK
  的文件 SHA-256、内部文件符号链接及目标 hash。拒绝外部链接、目录链接、不完整 JDK、
  非唯一或非 27 的 `JAVA_VERSION`；必须传已解析的真实 JDK 根目录。
- Wrapper 的仓库脚本与 properties 字节纳入清单；版本 3.9.16、URL、发布包 SHA-256、
  wrapperVersion/distributionType 必须匹配当前源码中的完整固定四属性，不接受重复或额外属性。
- `controlled_environment` 从零构造子进程环境，不读取、记录或复制 ambient 变量。
  只含 JAVA_HOME、HOME、TMPDIR、固定 PATH、LANG、LC_ALL、TZ、自行生成的 MAVEN_OPTS
  和 `MAVEN_SKIP_RC=1`（防止 Maven 全局/用户 mavenrc 重写受控参数）。
  不继承 JVM/loader 注入、provider 凭据、Maven 参数或用户 PATH。
- HOME/TMPDIR 必须是当前 session 新建的空目录，和 JDK 两两不相交。调用者须在 checkout
  外创建并独占这些目录。生成的 MAVEN_OPTS 显式设置 `user.home`/`java.io.tmpdir`，因为
  Java 不按 HOME 自动选择 user.home；目录仅接受 `/[A-Za-z0-9_./-]+`，避免 Maven shell
  分词或 glob 展开改变参数。
  直接诊断 JVM 不读取 MAVEN_OPTS，必须另外传 `jvm_directory_options` 的两个显式 argv。

身份必须由最终监督者独立保留，并在 build/使用前后重新计算比较。清单是字节身份，
不是发行方签名、编译器可信认证或实际加载证明。只设置环境不代表操作系统禁止网络；
固定 `/usr/bin` 中的 build 工具也没有被本组件认证。

## 验证与剩余门

十项小型人工测试覆盖环境注入、非空/重叠目录、Wrapper 配置漂移、JDK 版本/缺失输入、
native/脚本漂移、额外文件及内部/外部/目录链接。Linux JUnit 桥接强制全部十项执行且不得
skip；Windows 只运行适用的文件/配置用例，跳过部分不能作为 Linux 环境认证证据。
人工文件不是 JDK，也没有执行 Java 或读取历史 fixture。

本次额外修复 `test_fixed_witness_replay_process.py` 的既有测试辅助函数：真实 Linux CI
读取已回收进程的 `/proc/<pid>/stat` 时还可能在 read 阶段抛 `ProcessLookupError`（ESRCH），
原辅助函数只处理路径已移除的 `FileNotFoundError`。两种回收竞态均视为已停止；增加
ESRCH 与权限异常必须传播的确定性反例，不改生产 process helper 或扩大异常吞并范围。

仍缺：独立 clean Wrapper prepare、实际 JDK/Wrapper 版本进程观测与环境传递、普通 JVM
verify-only/诊断入口、真实 argv/loaded-class 来源、前后 source/classpath/toolchain 清单绑定、
独立保留 manifest digest、600/120/180/900 秒整体监督与 cleanup/finalize reserve、所有
artifact 和 pipe 的合计 10MiB 上限、实际 journal checker、静态兼容性刷新、整体最终
Review/CI 和唯一 session 登记。现有 process helper 尚不接收显式 env，后续必须接通并测试；
不得以本组件的纯函数测试替代真实子进程观测。

#140/GoalWindow 停止条件和生产/default/Q/guard/#95/#96/reserved/provider 边界不变。
本次不改变生产架构、运行方式或依赖，无需同步 README/AGENTS/ADR。
