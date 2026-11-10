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

## 失败、验证与限制

- 一次尝试，不重试open/send/wait失败；ESRCH/ECHILD都不代表已回收，不退累计额度。
- 部分pin或signal失败保留已取得身份、成功发送birth列表与原失败；不继续正常工作。
- 所有已拥有描述符最终各关闭一次；不重试close(EINTR)，关闭错误单独保留且不覆盖
  原错误。成功回收后关闭失败仍报告清理未完成。释放描述符不延长工作期限。
- 终态回收沿用原时限与共享stop计费；发送SIGKILL不释放live credit，真实terminal wait
  才释放。若仍观测到排队stop，先扣原计数后失败，不resume、不称整树已清理。
- 16项纯合成行为测试涵盖多进程完整顺序、失败锁存、部分获取/发送、期限跨越、
  birth/owner变化、未握手/未绑定/线程/未决创建拒绝、stop与ECHILD、关闭失败和一次性。
  Linux普通JUnit bridge要求16项全部通过且零跳过；Windows bridge仅提供编译验证。

未执行真实pidfd、signal、fork、ptrace、ABI探针、网络clean-build或历史22步。
本组件不处理未握手launch的安全终止、未决创建/线程EXEC清理及排队stop的完整drain；
不是完整监督器或COMPLETE收据。可信宿主/writer、冻结ABI身份与完整fixture仍待实施，
原整体最终Review/CI、compatibility/inputhash与唯一登记门继续有效。
