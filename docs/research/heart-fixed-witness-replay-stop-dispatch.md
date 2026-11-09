# 固定 witness：握手后的停点组合组件

接续 #205。#170 方案 A 与 #174 的具体 512 attempts / 128 live /
2,000,000 wait-stop 修订已获用户明确接受；不再以待接受作为工程阻塞。
本轮新增 `PostHandshakeDispatcher.accept`，仅限 test-only 监督器组件。

## 本次新增修改

- 只从已完成 CoordinatorLaunch 初始握手构造一次 owner；保留原单线程
  身份检查、原绝对 deadline 和同一 TaskLedger/StopReader。
- lazy wire 先让 StopReader 对原始 wait-stop 计费，再核验 owner/deadline、
  从 CreationSyscallDispatcher.stop_binding 获取 birth 独占 wire。未知 TID
  不进行 ptrace。每个 syscall stop 读取一次，再调用 syscall_info_stop。
- 创建入口先仲裁/计费，包括 clone3 固定 ENOSYS 干预；创建 event 使用本
  birth wire 的 GETEVENTMSG，先登记 child birth，再恢复 parent。
- 仅 coordinator 允许 SYSCALL resume，信号参数固定 0；恢复前再次核验
  owner/deadline。任何异常（含不确定 resume）锁存失败，不重试。
- 实际 terminal wait 经 birth 绑定后才报告 reap，释放对应 live credit。
  cleanup_tid 保留，不代表该数字仍可安全发信号，后续清理必须使用活 birth。

以上为 #206 的原始交付范围；本次 newborn 接纳增量见下节。

这是**部分组合**：所有普通 signal、EXEC/TID 迁移均
fail closed。newborn 的 terminal reap 可以结算，但不能在首次 stop 进行
任意 ptrace 或恢复。因而本组件暂不能完成真实 coordinator exec 或完整工作负载。

调用者必须是唯一实际 wait owner，同一个内核 wait tuple 只交付一次，
期间不另行 ptrace/resume；本 API 本身不能识别伪造或重复交付的 wait。
仍需实现有界 wait owner/loop、exec/普通信号策略、ABI 探针身份绑定、
全树 kill/reap、host/writer 传输以及完整冻结 fixture/task reservation 表。

## 验证及停止边界

原 #206 的 13 项 synthetic 回归覆盖单次 syscall read/debit/resume、实际 wire clone3
仲裁、child 登记顺序、未知身份与 deadline、wait cap、newborn 拒绝/结算、
signal/EXEC 拒绝、managed ptrace 拒绝和 resume 失败锁存。Linux Wrapper
bridge 已随下述增量要求 22 项全部通过、无 skip；Windows bridge skip 不作 Linux 证明。

## 本次新增修改：已绑定 newborn 的首次停止

`PostHandshakeDispatcher` 只在父任务的真实创建 event / GETEVENTMSG 已经
完成 lifecycle 登记后，将对应 birth ticket 标为待接纳。首次非 terminal
停点必须是 SIGSTOP，且 GETSIGINFO 为 `SignalInfo(19, 0, SI_USER=0, 0, 0)`。
此特征本身不是身份凭据；未知 TID 即使具有相同特征，也不得读取或恢复。
接纳后先 SETOPTIONS 完整冻结选项，再核验原 owner/deadline，最后仅一次
SYSCALL(signal=0)，不将初始 SIGSTOP 重新注入。接纳不额外 debit attempt/live，
wait-stop 仍先计费；原 newborn 的 successful-zero syscall exit 义务不变。

依据为 Linux v6.17 源码，而非将 coordinator 自发 SI_TKILL 套用于 newborn：

- [ptrace_init_task](https://github.com/torvalds/linux/blob/v6.17/include/linux/ptrace.h#L200-L216)
  对非 PT_SEIZED child 只设置 pending SIGSTOP 位。
- [collect_signal](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L553-L600)
  在没有 sigqueue 时填充 SI_USER、sender PID/UID 均为 0；
  [ptrace_signal](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L2731-L2747)
  将该 siginfo 带入 signal-delivery stop。

任何其他 siginfo、group-stop EINVAL、重复初始 SIGSTOP、提前 syscall/event、
选项或恢复错误均停止，保留 live credit 供真实清理；不重试不确定恢复。
新增 fake-kernel wire 回归检查 GETSIGINFO 一次、完整 SETOPTIONS 与恢复顺序，
另覆盖原 deadline、wait cap、未知身份、成功零返回义务和失败锁存。
首次 child stop 若先于父 event 被 wait owner 取到，本组件仍拒绝未知身份；
未来有界 wait owner 必须处理这种合法乱序，不能把本组件当完整 wait loop。
引用源码说明支持范围，不是当前 Linux kernel 的实际 capability 证明。

没有实际 fork/ptrace 探针、network/clean-build 人工验收或历史 22 步执行。
整体最终 Review/CI、兼容性/inputhash 和唯一登记仍先于真实执行。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default
停止门不变。生产架构/依赖/运行方式不变，README/AGENTS 无需同步。
