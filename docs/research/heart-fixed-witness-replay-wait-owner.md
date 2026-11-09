# 固定 witness：定向有界 wait owner

接续 #208；#170A 和 #174 具体修订均已明确接受。本次只实现 test-only
监督器组件，不执行探针或历史输入，不改变原预算、协议和执行登记门。

## 本次新增修改

`BoundedWaitOwner.run` 在已完成初始握手的 `PostHandshakeDispatcher` 上
一次性领取 wait 所有权，原单线程 tracer 在整个 session 内必须是唯一 waiter，
不得另有 SIGCHLD handler、ptrace 或 reap 调用。`waiting_births` 仅暴露
已绑定 live birth/TID 的不可变快照，不包括 tracer 或尚未有创建 event 的预留。

推荐路径为按 birth ticket 轮转的定向
`waitpid(tid, WNOHANG | __WALL | __WNOTHREAD)`，不使用 wait(-1)。
即使 child 的初始 SIGSTOP 先到达，也先留在内核；父创建 event 仍能通过
父 TID 的定向 wait 取到，完成 GETEVENTMSG/birth 独占 wire 登记之后才等待 child。
备选的 unknown tuple 暂存方案需要额外的一次性 debit/认证状态机，本次不引入。
父子都就绪时按 ticket 轮转，避免父任务持续有事件而饿死 child；每轮至多
accepted live cap 减去 tracer 的数量。没有事件才 sleep，最多 10ms 或原 deadline
剩余时间中的较小者；EINTR 不计 stop、不建立新 deadline。ECHILD/其他 wait 错误
停止且不释放 live credit，不重试该 session。

来源为 Linux v6.17 [kernel/exit.c](https://github.com/torvalds/linux/blob/v6.17/kernel/exit.c)：
`eligible_pid`（1118–1122）按 PID 类型精确匹配，`eligible_child`（1124–1148）
保留 traced clone 子任务，`is_effectively_child`/`do_wait_pid`（1605–1646）
分别覆盖 real-parent 与 ptrace-parent 的目标任务，`__do_wait`（1648–1692）
在 WNOHANG 时不进入阻塞等待。该源码依据不是当前主机能力验证。

每个实际返回的 stop 直接交给原 dispatcher，**先 debit，再核验 owner/deadline**；
wait 跨过截止或所有权变化也不漏计。错误目标 tuple 同样先计费，但不进行
ptrace/reap 结算。完整 newborn options-before-resume、真实 syscall-info、
clone3 ENOSYS 和预算仍由原组件负责。任何异常锁存 owner/dispatcher/launch
失败，保留清理身份，不重试不确定恢复；没有 signal/kill 操作。

## 验证与边界

19 项 synthetic 回归覆盖 child-first 排队、父子公平性、空轮次、deadline、
EINTR、ECHILD、错误/畸形 wait tuple、wait cap、所有权变化、EXEC/普通信号
拒绝、恢复错误锁存、独占 owner 与最终账本核对。所有 fork/wait/ptrace/sleep
均为 fake；新增 Linux JUnit bridge 将这 19 项纳入普通 Wrapper suite 且要求零 skip。
Windows 的 bridge skip 不提供 Linux 执行证据。

这是部分 wait loop：EXEC/TID/threadgroup 迁移、普通 signal/vfork 完整策略、
ABI 探针身份、whole-tree kill/reap、host/writer 和完整 fixture/task reservation
表仍未完成。定向 wait 遇到不支持的身份变化必须停止，不能猜新 TID；原宿主
watchdog 仍需约束内核调度/外部停顿，sleep/poll 不是硬实时保证。
原始启动握手仍由调用方提供初始 wait；本组件只从已完成握手开始。
drained 返回值仅为账本一致，不代表能力、清理证书或整套验收成功。

整体最终独立 Review/CI、兼容性/inputhash 与唯一登记仍必须先于真实
probe、network/clean-build 或历史 22 步。#140 BOUNDED_MISS、GoalWindow、
#95/#96、reserved/provider/runtime/default 停止门不变。生产架构和运行方式
不变，README/AGENTS 无需同步。
