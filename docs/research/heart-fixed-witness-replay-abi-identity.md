# 固定 ABI 反例的出生身份与一次性准入设计

本设计细化已明确接受的 [#174 第6条](heart-fixed-witness-replay-task-budget-proposal.md)，
不增加 ABI 例外、探针、原生执行次数或预算。仅设计，尚未实现；当前 dispatcher
继续拒绝所有 compat/x32 syscall。本次新增修改不改变生产架构，README/AGENTS无需同步。
源码基线：`51e34340442980229b85dd818eb507610541b3a2`。

## 问题与源码证据

`CreationSyscallDispatcher.syscall_info_stop`要求原生 syscall-info；原
`test_fixed_witness_replay_network.py` 的 `probe('x32')` 调用 `0x40000029`
（x32 socket），`probe('compat')`执行 `b866000000cd80c3`（i386 socketcall）。
当前直接组合会先在 tracer 拒绝，不能把这种拒绝误报成 seccomp 的 SIGSYS。

Linux v6.17 [`kernel/entry/syscall-common.c:35–46`](https://github.com/torvalds/linux/blob/v6.17/kernel/entry/syscall-common.c#L35)
先 `ptrace_report_syscall_entry`，然后 `__secure_computing`，并明确说明此顺序用于捕获
tracer 的修改。因此例外必须放行原入口，而非跳过调用、改返回值或主动发送 SIGSYS。
[`syscall_64.tbl:53`](https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_64.tbl#L53)
和 [`syscall_32.tbl:117`](https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_32.tbl#L117)
分别绑定 socket 41 和 socketcall 102，均非创建入口；i386 fork/clone/vfork分别为2/120/190。
[`kernel/seccomp.c:1339–1354`](https://github.com/torvalds/linux/blob/v6.17/kernel/seccomp.c#L1339)
的 KILL 路径终止任务；不得要求一个普通 syscall EXIT 才确认终止。
这些是静态依据，不是目标宿主能力结果。实际内核不能提供所需事实时停止，不切 ABI。

## 路径选择与范围

- 按 mode、进程名字或单独 syscall 号放行：不能区分其他 task 或复用 PID，排除。
- 为全部 compat/x32 建立通用创建仲裁：超出两条固定例外，排除。
- 推荐：固定源码/精确 argv 清单，加已认证 exec 的不可复用 birth，一次性入口票据；
  两条之外仍由现有 native guard 拒绝。实现分为纯匹配模型和真实认证接线；
  前者的成功不能独立授权 resume，后者未完成前不接通例外。

仅新增 test-only 组件及行为测试，必要修改现有 dispatch/stop/wait 接线；不改变网络
filter、拒绝表、clone3 ENOSYS、生产 API 或默认玩法。不提取或运行历史22步。

## 冻结清单与可信输入

执行前在同一 source/manifest 认证链冻结两条记录；每条包含模式、完整源码文件字节
SHA-256、canonical Python executable 路径/字节SHA-256、完整精确 argv 和允许的
ENTRY `(audit_arch, syscall_number)`。源码字节包括上述探针调用/机器码；不能只 hash
函数名字或反例标签。launcher/libseccomp/policy 继续使用原独立绑定，不被此记录替代。
预期值由冻结 checkout 和父侧可信 manifest 生成，不能用 tracee 自报值作为预期值。

| 模式 | 精确目标 argv 尾部 | ENTRY arch | 原 syscall 号 | 使用次数 |
| --- | --- | --- | --- | --- |
| x32 | `-I -B <canonical test_fixed_witness_replay_network.py> x32` | `0xc000003e` | `0x40000029` | 1 |
| compat | `-I -B <canonical test_fixed_witness_replay_network.py> compat` | `0x40000003` | `102` | 1 |

argv第一项必须是已绑定Python；表中的路径占位符在登记前变成精确绝对路径，不做basename、
前缀或可选参数匹配。完整 argv 既比较长度也比较每一项。源码/解释器 hash或路径变化立即
拒绝，不能在运行中重新冻结。路径归一化不能替代字节校验。

唯一 tracer 在真实创建事件和 EXEC 迁移完成后，将该预留 probe 槽与当前 live birth、
group_birth、TID及exec代次绑定，再从该停点读取真实 `/proc` exe/cmdline 与文件字节，
与冻结记录比较；不启动 shell/Git/辅助观察进程。每个模式只允许绑定一次，binding不是
可转交给其他birth的字符串。exec entry/event本身仍走native认证，无例外。

文件/argv认证是本项目协作式进程模型中的身份依据，不证明Python实际执行了哪些指令，
也不是恶意tracee或恶意宿主的内存代码证明。可信host须保持冻结文件不被并发修改、
所有wait/ptrace由唯一owner持有；无法建立此前提则拒绝准入。不得把纯模型传入的hash
或`authenticated=True`当成真实认证。

## 一次性状态与调度契约

1. `UNBOUND -> BOUND`：只有上述认证及原owner/deadline/预算检查全部完成，才绑定
   清单中的预留槽。失败锁存原session；不新建预算，不增加task，不退款或重新绑定。
2. `BOUND`期间普通native入口/出口仍走现有完整创建仲裁和sequence校验。
   收到非原生ENTRY时，先经StopReader扣共享stop额度，再核对当前birth/TID/exec代次、
   清单hash、精确arch/number和未消费状态。任何错配均停止。创建syscall即使来自probe
   也不能走例外；不能根据32位截断、掩掉x32 bit或猜测ABI来比较。
3. 完全匹配时先原子消费票据为`AWAITING_SIGSYS`，再仅执行原来的SYSCALL resume，
   signal=0。不写register、不改syscall/errno、不补发signal。恢复结果不确定则失败，
   票据仍已消费，不能重试。外层执行这一步仍必须复核原owner/deadline。
4. `AWAITING_SIGSYS`不允许任何后续syscall ENTRY/EXIT、创建或EXEC，不把它交回普通
   native sequence（后者仍持有特殊入口状态）。允许现有认证信号处理路径保留真实
   SIGSYS的原siginfo并原信号恢复；不能要求必然出现delivery stop，不能注入SIGSYS。
   其他普通信号不被解释为成功；按既有信号策略处理但不释放票据/额度，不延长截止。
5. 唯一owner真实wait并认证该birth的`signaled(SIGSYS)`后，先正常reap结算，再标为
   `KERNEL_TERMINAL_OBSERVED`。不要求EXIT；exit(0)、其他终止信号、ECHILD、伪造wait、
   丢失birth或清理主动SIGKILL均不能成功。仅该终态也不能独自证明过滤器：最终checker
   必须同时绑定原filter安装/来源链、entry原样放行记录、无注入记录和清单hash。

探针发生额外创建/EXEC即本反例身份失效；已存在子任务仍按原账本清理，不推断reaped。
状态改变不能清除任何既有失败锁存。失败后normal dispatch不再可用，使用独立cleanup。
ledger的正常live释放只来自真实reap；两条例外不跳过任何task或stop计费。

## 有限实施验证与停止条件

实现PR先用假transport覆盖以下12组行为，每组均可含有限表驱动子例；不加载libc、
不fork/ptrace/mmap执行或触发原KernelTest：

1. 两条精确ENTRY可各消费一次，输出事实不携带独立resume权限。
2. mode/argv项数、参数、路径、源码与解释器hash各自错配时不准入。
3. 未绑定、其他birth/TID/group_birth、旧exec代次与复用PID拒绝。
4. 第二次绑定、第二次ENTRY及跨模式票据复用拒绝。
5. compat/x32的fork/clone/vfork/clone3、未知ABI/号码全部拒绝。
6. 非ENTRY、截断syscall-info、类型和整数宽度错误拒绝。
7. BOUND内正常native创建仍预留且按原失败/live规则结算，不走例外。
8. 例外恢复失败、owner/deadline改变时不重试，不退款，不写register。
9. 例外后ENTRY/EXIT/EXEC/创建均失败，无native sequence回退。
10. 有/无SIGSYS delivery stop的真实形状合成路径均能终态结算，不伪造signal。
11. 错信号、正常退出、ECHILD、主动清理、错误birth终态均不得形成成功证据。
12. shared stop耗尽和既有失败锁存优先于例外；证据输出仍共享预留writer。

新增纯合成suite最多30秒；Linux普通JUnit bridge要求全部组实际通过、零skip，
Windows结果明确区分编译/平台skip。不新增真实探针或普通CI内原生能力测试。
实现的最终完整diff独立Review、适用CI和正常合并后，才将该组件列为已交付。

实际人工接线仍使用原完整清单中的两条probe，不重复运行、不另加次数；每probe≤5秒、
原组件50秒/人工全套900秒及阶段限制、512尝试/128live/2,000,000stop、证据10MiB与
writer分区均保持。全套固定fixture/task预留表、可信host/writer、复杂整树清理、最终
整体Review/CI、compatibility/inputhash与唯一登记全部完成前，不执行任何真实探针。
失败按原CAPABILITY_UNAVAILABLE/IMPLEMENTATION_INVALID/INCOMPLETE分类停止，不加预算、
不换ABI/seed/环境重跑。#140 BOUNDED_MISS、GoalWindow、#95/#96及runtime/default门保持。

本设计完成条件是两个文件的最终diff经独立Review、适用CI、正常合并；不是模型已实现、
内核能力通过或完整人工验收完成。下一步实施纯匹配/消费模型，随后才接真实身份认证。
