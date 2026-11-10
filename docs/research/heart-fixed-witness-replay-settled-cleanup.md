# 已认证独立进程的 pidfd 终止与回收

本组件实施已接受 #174 中的失败清理子路径；#170A 存储分类、累计512次创建尝试、
同时128存活、2,000,000次wait-stop、原时限及证据10MiB均不变。

## 身份与源码依据

`SettledProcessCleanup.run` 组合既有 `CleanupReaper`，先永久撤销正常监督，
只接受已通过真实初始握手、dispatcher仍保有birth绑定、无pending creation、
每个group_birth只有一个存活birth且TID等于原TGID的库存。逐一取得pidfd后，
才向全部已固定描述符发送SIGKILL，最后进入原有正TID终态回收。
不从 `cleanup_tid` 推断身份，不调用kill(pid)、wait(-1)、ptrace resume或信号注入。

Linux v6.17 [`kernel/signal.c` 2232–2253](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L2232)
的SIGCHLD忽略/SA_NOCLDWAIT自动回收分支明确要求 `!tsk->ptrace`。
已绑定、未被唯一tracer回收的单进程birth因此不能在pidfd取得前被该分支释放PID；
拒绝未握手launch尤其重要：只有fork返回数字的子进程可能在TRACEME前死亡并自动回收。
同时拒绝多存活线程和未决创建，避免非leader EXEC静默退休或未知子任务破坏该证明。
可信单线程宿主仍须保证没有signal callback/外部reaper调用wait或detach；本组件不建立
完整可信宿主，也不允许把合成Mock当作真实绑定证据。

同版本 [`kernel/pid.c` 631–662](https://github.com/torvalds/linux/blob/v6.17/kernel/pid.c#L631)
说明 `pidfd_open(pid, 0)` 只接受组leader，取得CLOEXEC描述符；
[`kernel/signal.c` 4089–4108](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L4089)
从pidfd提取持有的pid对象，flags=0且未用PIDFD_THREAD时按TGID发送。
获取前后及每次发送前后复核原owner/deadline和完整birth库存；取得后只向描述符发送，
不重新查找数字PID。全部描述符在任何发送前取得；失败不会退回kill(pid)。

`CoordinatorLaunch.unsettled_terminal_waits` 保留正常wait/dispatch已经消费、但尚未
认证结算的terminal PID；记录发生在tuple、reader、owner/deadline与birth验证之前。
只有成功的认证reap才清除。库存命中此标记时，pidfd路径在获取前拒绝，避免真实wait
已经释放PID但ledger仍保留live credit的错误路径。未认证terminal不被伪称为reaped。

## 失败、验证与限制

- 一次尝试，不重试open/send/wait失败；ESRCH/ECHILD都不代表已回收，不退累计额度。
- 部分pin或signal失败保留已取得身份、成功发送birth列表与原失败；不继续正常工作。
- 所有已拥有描述符最终各关闭一次；不重试close(EINTR)，关闭错误单独保留且不覆盖
  原错误。成功回收后关闭失败仍报告清理未完成。释放描述符不延长工作期限。
- 终态回收沿用原时限与共享stop计费；发送SIGKILL不释放live credit，真实terminal wait
  才释放。全部pidfd发送成功后冻结原birth库存，仅这些birth的合法signal/syscall stop
  可先扣原计数再忽略，继续正TID轮询，不resume、不注入信号、不创建或结算任务。
  ptrace事件仍失败关闭，尤其不把创建/EXEC事件当作无害stop；独立CleanupReaper未被
  该组合路径授权时仍拒绝所有stop。累计drained_stops与last_drained_stop保留有界证据，
  不逐事件分配无限列表。无进展轮次仍最多睡10ms并受原deadline限制。
- cleanup消费terminal后同样在任何后续验证前写入unsettled_terminal_waits；仅认证reap
  成功才清除相应PID。期限、目标、birth或reap失败时保留标记，后续库存拒绝该歧义身份。
- 30项纯合成行为测试涵盖多进程完整顺序、失败锁存、部分获取/发送、期限跨越、
  birth/owner变化、未握手/未绑定/线程/未决创建拒绝、stop与ECHILD、关闭失败和一次性。
  真实initial admission收到terminal而失败时，admitted只表示尝试，必须核对handshake.completed
  及dispatcher绑定，防止打开已被该wait回收的数字PID。
  还覆盖post-handshake失败terminal、wait目标错配时的保留及成功reap清除。
  新增排队stop后终态、轮询公平性、共享预算耗尽、deadline、未知/错目标/ptrace事件拒绝、
  birth变化与cleanup消费terminal后失败的隔离证据；不调用ptrace读取或resume。
  Linux普通JUnit bridge要求30项全部通过且零跳过；Windows bridge仅提供编译验证。

Linux v6.17 [`kernel/signal.c:723–735`](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L723)
说明TASK_WAKEKILL唤醒stopped/traced任务；同文件
[`1003–1027`](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L1003)对SIGKILL建立
group exit并唤醒，且[`2371–2377`](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L2371)
在fatal signal pending时不进入ptrace睡眠。因此已发送SIGKILL后的排队普通stop不需要
PTRACE_CONT/SYSCALL才能继续等待终态。这些源码依据不代表真实宿主能力验证；任何
stop耗尽原预算或超过原期限仍失败，不重试、重置或追加清理时间。

未执行真实pidfd、signal、fork、ptrace、ABI探针、网络clean-build或历史22步。
本组件不处理未握手launch的安全终止、未决创建/线程EXEC清理及ptrace事件drain；
不是完整监督器或COMPLETE收据。可信宿主/writer、冻结ABI身份与完整fixture仍待实施，
原整体最终Review/CI、compatibility/inputhash与唯一登记门继续有效。
