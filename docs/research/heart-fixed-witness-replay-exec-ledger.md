# 任务监督：EXEC 身份账本迁移

本次新增修改接续 #210，实施用户已接受的 #174 累计任务政策。
`TaskLedger.exec_identity(birth, old_tid, new_tid)` 只处理调用方已认证的事实，
不读取内核、不授权 resume、不安装到 PostHandshakeDispatcher；真实 EXEC 继续失败关闭。

Linux v6.17 的 [fs/exec.c de_thread](https://github.com/torvalds/linux/blob/v6.17/fs/exec.c#L908)
先终止其他线程；非 leader 执行线程接管当前 leader 的数值 PID，旧 leader 变成
EXIT_DEAD，并由 release_task 静默 ptrace_unlink（995–1021 行）。
[kernel/exit.c](https://github.com/torvalds/linux/blob/v6.17/kernel/exit.c#L1481)
在仍有子线程时不 reap group leader。因此不能凭旧 TID ECHILD 当作普通终态 reap，
也不能期待旧 leader 总会有独立终态 wait。源码模型不能代替冻结平台能力验收。

账本保留原 group_birth 与原始 TGID，另外跟踪当前 leader birth。已认证的非 leader
EXEC 将存活执行 birth 的 tid 改为原 TGID，旧当前 leader 标为 `exec-replaced`，
只释放该 leader 的 live 槽，累计 attempts 不退款。实际终态 wait 才标为 `reaped`。
不可变身份快照带 terminal 分类；旧 birth 的重复 reap 失败，不释放新身份的槽。
连续 EXEC 可更换当前 leader，原始组出生身份不变；旧执行线程的数值 TID 可以复用，
但不能替换历史 birth。leader 自己 EXEC 不改变预算。

保守前置：执行 birth 存活、old TID 精确一致、new TID 等于原 TGID；组内所有未完成
creation 必须已解决；除执行者和被替换当前 leader 外，所有组员须已记录实际终态 reap。
前置失败先锁死账本，身份与 live 不部分修改；已停止账本不接受迁移。不能从 EXEC
事件推断其他 sibling 的 reap，也不能从组 birth 推断操作系统身份。原 leader 已 reap
而尚有线程的合成状态不允许首次接管。外部父进程尚未读取 fork result 可继续匹配
原子进程 TID，旧 leader 的历史记录保持不变。

10 项新增 synthetic 回归覆盖 leader/nonleader、连续接管、兄弟终态、pending、
错误 birth/TID/类型、停止账本、重复 reap、数值复用与外部父进程未读 result。
Linux Wrapper bridge 要求全部 30 项 ledger 测试通过且无 skip；无真实 fork/ptrace。
生产源码、workflow、冻结协议和原 guard 不变，README/AGENTS 的架构说明无需调整。

下一步须认证 native exec entry、EXEC stop 与 GETEVENTMSG 的 old TID，绑定精确 birth；
同时更新 dispatcher、wire/token/sequence、targeted wait 和 cleanup 身份，才能开放 resume。
原 wait owner 可能在旧 TID 遇到 ECHILD，须按源码处理，不能吞掉该错误或释放 live。
初始握手有界等待、信号/vfork、全树清理、冻结 ABI 探针、可信宿主/writer、完整 fixture
仍待完成；整体最终 Review/CI、兼容性/inputhash/唯一登记前，不执行真实能力验收、
完整 network/clean-build 或历史 22 步。#140/GoalWindow/#95/#96 与 runtime 停止门保持。
