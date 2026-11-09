# 任务监督：出生身份与线程组元数据

本次新增修改接续 #209 的 targeted wait owner，属于用户已接受的 #174 实施范围。
`TaskLedger.reserve(..., thread=...)` 在创建入口固定线程关系；
`CreationPolicy.arbitrate` 从已解码的 native legacy clone flags 传入 CLONE_THREAD。
原有 flags 仲裁继续在返回义务前执行；非法 flags 的 attempt/live 不退款。
fork、vfork、无 CLONE_THREAD 的 clone（包括 CLONE_VM）均创建独立线程组。
clone3 仍固定拒绝为 ENOSYS，不读取 clone_args 或建立子身份。

`TaskLedger.created` 绑定 birth/TID 时记录 group_birth：线程继承父任务的组出生
ticket，进程以自己的 birth 为组身份。`identity(ticket)` 返回不可变快照，包含
birth、tid、group_birth、原始 tgid 和 alive；仅按已知 birth 查询，在失败清理阶段
仍可读取。它不认证当前内核身份，也不授予 ptrace、resume、signal 或 reap 权限。
每个线程仍独占 live/attempt 槽；leader 的终态 reap 不释放其他线程槽。
历史 leader birth 保留，防止同一数值 TID 被复用后混淆不同组。

来源：[Linux v6.17 kernel/fork.c copy_process](https://github.com/torvalds/linux/blob/v6.17/kernel/fork.c#L2245)
在 CLONE_THREAD 时继承 current 的 group_leader/tgid，否则令 group_leader=p、
tgid=p->pid。该来源用于静态关系模型，不能代替冻结平台的原生能力验收。

范围与验证：新增 8 项 synthetic 行为回归及已有 lifecycle 事件分类断言，覆盖
线程创建线程、线程创建共享 VM 进程、leader reap 后组身份与逐线程计费、
历史 TID 复用、快照不可变和非法关系/flags。普通 Linux Wrapper 桥接要求
20 项 ledger 和 14 项 lifecycle Python 测试全部通过且无 skip。
测试不 fork、不 ptrace、不启动原生探针或历史输入。

**尚未实现 exec 迁移。** 当前 tgid 是创建时的数值，不可在 EXEC 后继续使用。
PostHandshakeDispatcher 和 bounded wait owner 仍在 EXEC 停点失败关闭，
不能改写 TID、推断其他线程已 reap、释放 live 或凭 cleanup_tid 发信号。
下一步需结合 PTRACE_EVENT_EXEC old-TID、de_thread 和真实终态 reap 实现迁移及
wait 选择；初始 handshake 有界等待、普通信号/vfork、全树清理、冻结 ABI 探针、
可信宿主/writer 与完整 fixture/task 表仍待完成。
整体最终 Review/CI、兼容性/inputhash/唯一登记前不得执行真实能力验收、
完整 network/clean-build 或历史 22 步；#140/GoalWindow/#95/#96 与采用停止门保持。
生产结构和默认行为不变，README/AGENTS 架构说明无需调整。
