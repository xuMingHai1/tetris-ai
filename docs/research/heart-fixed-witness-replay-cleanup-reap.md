# Fixed-witness 失败后的终态回收组件

本组件继续落实已明确接受的 #170 方案 A 与 #174 具体修订；不新增政策例外。
`CleanupReaper` 接管 `CoordinatorLaunch` 的唯一 wait 所有权，永久禁止正常运行路径，
在**原 deadline** 内对已结算的 process birth 使用正 TID、
`WNOHANG | __WALL | __WNOTHREAD` 等待。它只消费真实终态，不发送信号、不 ptrace、
不恢复任务、不创建进程，也不返回 COMPLETE 或能力证明。

正常 `StopReader` 在失败后拒绝输入，不能作为失败清理入口。本组件直接使用共享账本，
允许各层已锁存失败时结算真实终态；所有原错误保持不变。cleanup stop 在 owner、
deadline 和 tuple 校验前扣同一个 wait-stop 计数，达到原上限即停止；没有新计数器。
终态只释放实际 birth 的 live 槽，不退累计尝试。创建 dispatcher 前 wire 初始化失败，
但 coordinator 已登记到 ledger 的路径也可回收。

## 身份边界与停止条件

- 不使用 `cleanup_tid` 发信号，也不把它作为身份认证。保留该历史值，终态归属来自
  同一专用单线程 tracer 的定向 wait 与未回收 birth。没有 wait(-1) 或未知 TID 接纳。
- 仅接受所有创建已结算、每组只有一个 live birth 且其 TID 等于原 TGID 的 inventory。
  未决创建可能已有尚未消费的子事件；多线程 exec 可能静默撤销旧 leader。因此这些
  状态在任何 wait 前拒绝，保留 live/pending，不猜测子身份或宣称已完整清理。
- `ECHILD`、错误目标、未知 status、观测到 stop、owner/deadline 失效均为不完整。
  不推断已 reap，不 retry，不通过 signal injection 解决停止。`last_wait` 保留最后一次
  消费结果，`reaped` 只记录完成账本结算的终态；跨 deadline 消费终态也不能报成功。
  cleanup也在后续验证前隔离已消费terminal PID，成功认证reap才清除，避免失败后复用。
  只有[组合pidfd清理](heart-fixed-witness-replay-settled-cleanup.md)已成功发送SIGKILL的
  精确birth库存可以drain普通signal/syscall stop；独立调用仍拒绝stop，ptrace事件始终拒绝。
- idle/EINTR 不产生额度，最多 sleep 10ms 且不超过原剩余时限。没有额外清理窗口。
  原宿主 watchdog、已预留清理时间、计费 writer 和失败证据输出仍由后续宿主承担。

内核语义依据为 [Linux v6.17 kernel/exit.c](https://github.com/torvalds/linux/blob/v6.17/kernel/exit.c)：
`eligible_pid`/PIDTYPE_PID 选择与 `__WNOTHREAD` 限制提供定向单 owner wait；
`wait_task_zombie` 终态消费与 `release_task` 说明真实 reap 才能结算。
这是源码核对，不是本机 ptrace、信号或 wait 能力实测。

## 验证与后续

16 项 fake wait 回归覆盖锁存失败后的真实回收、进程树逐 birth 结算、初始 wire 失败、
接管后正常路径禁止、idle/EINTR/原时限、跨时限/owner stop 仍扣费、共享额度、
错误目标/畸形结果/ECHILD、未决创建与线程迁移拒绝。Linux JUnit bridge 在普通
Wrapper 测试中要求这些测试全部执行且零 skip；Windows bridge 跳过不作 Linux 证明。

尚未实现整树 kill、复杂创建/线程 exec 的失败清理、冻结 ABI 探针绑定、可信宿主与
writer 传输、完整 fixture/task 预留表。后续应把终止信号的不可复用身份与这些复杂路径
接入同一 cleanup owner；不能用本组件的成功返回替代整树清理证明。
整体最终独立 Review/CI、兼容性/inputhash、唯一有限登记完成前，不运行原生 probe、
完整 network/clean-build 验收或历史 22 步。#140 BOUNDED_MISS、GoalWindow、#95/#96、
reserved/provider/runtime/default 停止门保持不变。
