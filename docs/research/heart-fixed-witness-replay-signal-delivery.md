# 固定 witness：普通信号递送

#170 方案 A 和 #174 的具体任务预算修订已获用户明确接受。本次接续 #213/#214，
仅修改 test-only 监督器的 `PostHandshakeDispatcher.accept` 普通信号路径。

## 本次新增修改

原实现拒绝所有普通 signal stop，不能让 coordinator 接收正常 SIGCHLD，
也不能让原 seccomp 产生的 SIGSYS 按真实内核路径终止被测进程。
现在先通过同一 StopReader 对实际 wait-stop 计费，再认证当前 birth/wire、
原 owner/deadline，并对该 TID 执行一次 GETSIGINFO。成功的非作业控制
signal-delivery-stop 以 **相同信号号** 执行一次 PTRACE_SYSCALL。
不调用 SETSIGINFO，不改写原 siginfo，不新增 attempt/live，不重置 syscall
entry/exit 状态。恢复前再次检查原 owner/deadline。

这不是根据 wait signal 猜测递送停点：GETSIGINFO 失败（包括 group-stop
EINVAL）立即锁存失败，未知 birth 在读取前拒绝。newborn 的首次 SIGSTOP
仍走已有专门认证，完整 SETOPTIONS 后只以 signal=0 恢复一次。
终止信号的递送本身不释放 live；只有后续认证的真实 terminal wait 才结算。
不确定 resume 不重试，保留原清理身份和已计费事件。

依据为 [Linux v6.17 ptrace_signal](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L2731-L2774)：
signr=0 会取消信号，改变 signr 会重写 siginfo；相同 signr 保留原内核信息。
信号递送和 group-stop 的区分亦见 [ptrace(2)](https://man7.org/linux/man-pages/man2/ptrace.2.html)。
源码依据不代表当前宿主的实测能力。

## 验证与限制

stop-dispatch 合成套件由 32 增至 41 项，Linux Wrapper bridge 同步要求全部
通过且无 skip。新增回归覆盖非停止信号（含 SIGTRAP、SIGCHLD、SIGCONT、
实时信号）的原号递送、真实 wire SIGSYS/siginfo 不改写、信号处理程序后续
fork 仍先仲裁、错误/身份/deadline/事件上限、恢复失败锁存和实际 reap 才释放
live。它们均为 fake-kernel/synthetic，不启动真实 fork/ptrace 或 ABI 探针。

**风险与未完成项：** SIGSTOP/SIGTSTP/SIGTTIN/SIGTTOU 以及 group-stop
继续 fail closed，不以 signal=0 强行唤醒；SIGKILL 不存在 delivery-stop，
若出现该伪停点则拒绝。这是普通非停止信号支持，不是完整 job-control
策略。全树失败清理由另一组件实施；ABI 探针身份、host/writer 传输、完整
冻结 fixture/task 预留表和整体验收仍须完成。

没有 native capability、network/clean-build 人工验收或历史 22 步执行。
整体最终独立 Review/CI、兼容性/inputhash 及唯一登记仍先于真实执行。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default
停止门不变。生产代码、冻结协议、依赖、运行方式不变，README/AGENTS
无需修改；研究索引增加本组件入口。
