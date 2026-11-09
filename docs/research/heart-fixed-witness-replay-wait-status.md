# 本次新增修改：监督器 wait 状态解码

接续用户明确接受的 [#174 任务政策](heart-fixed-witness-replay-task-budget-proposal.md)。
`scripts/fixed_witness_replay_wait.py` 的 `decode_wait_status` 只解码 Linux
waitpid 返回的状态位；不执行 wait、ptrace、创建任务或恢复 syscall。

区分正常退出、信号终止及 core bit、syscall-stop、普通 signal-stop，以及原
TRACEME options 启用的 FORK/VFORK/CLONE/EXEC 事件。拒绝额外位、非法信号、
未知事件、未启用的 VFORK_DONE/EXIT/SECCOMP/SEIZE STOP 与 continued 状态。
事件必须携带普通 SIGTRAP，不能将 SIGTRAP|0x80 当作创建事件。

返回值不提供恢复许可。普通 SIGTRAP、group-stop 与 signal-delivery-stop 不能仅凭
wait word 区分；未来 dispatcher 必须结合 GETSIGINFO、启动握手和身份状态。
syscall-stop 必须再读 GET_SYSCALL_INFO，不使用奇偶位猜入口/出口。
创建事件仍需 GETEVENTMSG、出生绑定和 pending 预留认证；线程 exec 重绑定仍待实现。
未来循环必须在进一步操作前对每个 wait-stop 计费，包括本解码器拒绝的停点。

11 项合成测试穷举退出码、Linux 信号及 core bit，覆盖 syscall/event 混淆、
不一致位和未知状态。Java Linux 桥接仅将这些测试接入普通 Wrapper CI。
这不是实际 wait/ptrace、EXITKILL 或整套 network/clean-build 能力验证。

本组件四个新文件，不改变生产架构、README/AGENTS、冻结预算或执行登记。
宿主集成、真实事件监督、完整固定 fixture/task 表、整体最终 Review/CI、兼容性及
唯一登记仍须完成；本轮不运行真实探针或历史输入。#140 BOUNDED_MISS、GoalWindow、
#95/#96、reserved/provider/runtime/default 停止门保持。
