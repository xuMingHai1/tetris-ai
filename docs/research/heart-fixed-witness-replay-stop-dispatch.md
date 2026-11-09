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

这是**部分组合**：newborn 初始 stop、所有普通 signal、EXEC/TID 迁移均
fail closed。newborn 的 terminal reap 可以结算，但不能在首次 stop 进行
ptrace 或恢复。因而本组件暂不能完成真实 coordinator exec 或完整工作负载。
本轮不猜测 newborn 的 siginfo 语义，不把原始 StopFacts 当作可传递凭据。

调用者必须是唯一实际 wait owner，同一个内核 wait tuple 只交付一次，
期间不另行 ptrace/resume；本 API 本身不能识别伪造或重复交付的 wait。
仍需实现有界 wait owner/loop、newborn/exec/信号策略、ABI 探针身份绑定、
全树 kill/reap、host/writer 传输以及完整冻结 fixture/task reservation 表。

## 验证及停止边界

13 项 synthetic 回归覆盖单次 syscall read/debit/resume、实际 wire clone3
仲裁、child 登记顺序、未知身份与 deadline、wait cap、newborn 拒绝/结算、
signal/EXEC 拒绝、managed ptrace 拒绝和 resume 失败锁存。Linux Wrapper
bridge 要求 13 项全部通过、无 skip；Windows bridge skip 不作 Linux 证明。

没有实际 fork/ptrace 探针、network/clean-build 人工验收或历史 22 步执行。
整体最终 Review/CI、兼容性/inputhash 和唯一登记仍先于真实执行。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default
停止门不变。生产架构/依赖/运行方式不变，README/AGENTS 无需同步。
