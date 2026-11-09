# 累计任务监督：创建入口仲裁组件

本次新增修改接续已接受的 [#174 精确政策](heart-fixed-witness-replay-task-budget-proposal.md)
和 #192 的 `TaskLedger`。#170A 与 #174 已获用户明确接受；不是待授权事项。
组件限定 test-only、纯内存、无外部调用，不启动真实 syscall、tracee 或能力探针。

`scripts/fixed_witness_replay_creation.py` 的 `CreationPolicy.arbitrate` 接收未来 tracer
已认证的 native x86-64 创建入口、六个原始寄存器参数和父出生 ticket，先调用同一
`TaskLedger.reserve`，再返回不可变义务。fork/vfork/legacy clone 预留 attempt/live；
clone3 仅扣 attempt，返回固定 ENOSYS 干预义务，不解引用不可信用户内存，也不自行
伪造结果或完成账本。真实 syscall-info、寄存器干预与 ENOSYS 出口认证仍由独立适配器负责。

legacy clone 只接受显式标志集合：VM/FS/FILES/SIGHAND/VFORK/THREAD/SYSVSEM/SETTLS/
PARENT_SETTID/CHILD_CLEARTID/CHILD_SETTID；拒绝 UNTRACED、PARENT、namespace、pidfd、
历史/未知标志。SIGHAND 要求 VM；线程要求 SIGHAND、零 exit_signal 且不得 VFORK；
进程 clone 仅接受 Linux SIGCHLD（17）。超出此保守支持范围直接停止，不证明所有
libc/JVM 组合均兼容。fork/vfork 本身无 flags 参数，不解释未使用寄存器。

事件义务依 Linux ptrace 创建事件选择顺序区分：legacy clone 带 VFORK 预期 VFORK，
否则 SIGCHLD 进程预期 FORK，线程预期 CLONE；不能仅凭 syscall 名称绑定子事件。
本组件尚不接收/认证事件。拒绝的已识别创建尝试仍永久扣费，pending/live 保持到
父终态 reap，不能通过拒绝 flags 退款。任何政策错误永久锁存；caller 必须停止
恢复整个树并进入清理，不能继续使用裸账本发放许可。quota 失败也不返回义务。

compat/x32、未知 syscall、截断/非整数寄存器不获义务。本入口只处理创建调用，
不是可放行全部其他 syscall 的 dispatcher；原冻结非创建 ABI 探针例外仍待完整
dispatcher 认证绑定。尤其不能用本模块缺少某 syscall 就把它当普通安全调用放行。

11 项合成测试覆盖 Linux event 类型差异、pthread/spawn 组合、clone3 不读内存、
UNTRACED/关系改变/未知 flags、非法线程组合、ABI、额度不足、重复 pending 和失败不退款。
普通 Linux Wrapper 桥接执行这些纯 Python 测试；Windows 的 Java 桥接按平台跳过。
这些测试既不是已关闭人工清单的新执行，也不证明创建前强制能力。

剩余完整实现：实际 ptrace 传输与 stop/event/result 认证、线程 exec/TID 重绑定、
全树 EXITKILL/清理、report writer 分区、可信宿主和固定 fixture/task 预留表。
整体最终 Review/CI、唯一有界验收登记前不运行真实探针或 network/clean-build 验收；
历史 22 步继续受来源/兼容性/inputhash/唯一登记原门禁约束。本组件不修改生产结构、
游戏或 UI，README/AGENTS 架构说明无需调整。
