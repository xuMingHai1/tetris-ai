# vfork 组合监督的合成验证

本组件验证已接受 #174 的既有约束：父 vfork 等待子 exec/退出时，不能只等待
父 syscall 出口而饿死子任务。#170A 的存储分类和 #174 的累计512次尝试、同时
128存活、2,000,000次wait-stop、原时限和证据10MiB不变；不增加运行预算。

## 真实源码依据与现有接线

Linux v6.17 [kernel/fork.c](https://github.com/torvalds/linux/blob/v6.17/kernel/fork.c#L2595)
根据 CLONE_VFORK 优先选择 VFORK 事件；2640–2649 行先报告创建事件，再
`wait_for_vfork_done`，最后返回子 PID。TRACEVFORK_DONE 未被当前冻结 options
启用，因此不新增该事件，也不等待它来推进状态。

`CoordinatorLaunch.launch` 先预留协调器；`InitialWaitOwner.run` 在原 owner/deadline
下完成握手并移交 dispatcher；`BoundedWaitOwner.run` 对已绑定 birth 做正 TID
WNOHANG 轮询。父出口不可用返回0，继续轮询子任务；未知子不被 wait(-1) 消费。
`PostHandshakeDispatcher.accept` 复用已有 newborn admission、exec entry/event/exit
和终态 reap。子 exec 不释放 live credit，实际终态才释放；父 pending 创建义务
持续到匹配结果，允许子先 reap。当前路径已具备这些行为，无需另加 vfork planner
或第二个等待线程。本次仅修正 wait owner 中已失效的普通信号说明。

## 本次新增验证

`test_fixed_witness_replay_vfork_session.py` 从 fake launch、初始有界等待到正常
有界等待串接，所有系统调用、ptrace、时钟和 wait 都注入 fake。10项行为测试覆盖：

- native vfork 与 legacy clone(VM|VFORK|SIGCHLD)，分别接 execve/execveat；
- 子 exec 先于父结果、父结果先于观测到的子 EXEC、子退出/致命信号先于父结果；
- 子 exec 失败后重试，及 pending 父任务收到 SIGCHLD 后保持原结果义务；
- pending vfork 耗尽原时限、子 EXEC stop 跨时限先扣计数再拒绝访问；
- 共享停点额度到界阻止读取 EXEC facts，累计创建额度不足阻止恢复入口；
- 子已 reap 后父结果错配仍保留父 credit/pending，锁存且不重试。

正向路径核对 attempts/live/pending、每个 stop 恰计一次、父子各自 wire、
options、signal resume、完整 drain 与单次协调器 fork。顺序 fake 只在指定 TID
可用时给出下一事件，防止用总是就绪的父出口掩盖子任务饥饿。普通 Linux JUnit
bridge 要求10项全部通过且零跳过；Windows bridge 跳过只证明编译。

这些安排是允许的代表时序，不是全部内核调度的枚举。未启动真实 vfork/wait/
ptrace/ABI probe，也未执行网络 clean-build 或历史22步。合成成功不证明真实
内核兼容、实时期限、完整清理或 COMPLETE。整树终止与线程/未决创建清理、
job-control policy、冻结ABI探针身份、可信host/writer与完整fixture仍待接入；
原整体最终Review/CI、compatibility/inputhash和唯一登记门不变。
