# Coordinator 初始有界等待组件

本次新增修改：`InitialWaitOwner` 对已经由 `CoordinatorLaunch` 启动并登记的
coordinator，独占消费其实际首次 wait。只以实际 PID 调用
`waitpid(WNOHANG | __WALL | __WNOTHREAD)`，不使用 `wait(-1)`，不重新 fork。
idle/EINTR 最多睡眠 10ms 且不超过原 deadline 剩余时间；EINTR 不计 stop，
不创建新预算。其他 wait 错误、错误身份、非可信初始信号、超时与不确定 resume
均停止，不重试、不归还未确认 reap 的 live credit，保留 cleanup 身份。

实际 stop 进入 `accept_initial_wait`，先共享计费再认证；wait 跨越 deadline 或
owner 变化时仍计费，但不访问 task。`CoordinatorHandshake` 接受 launcher 的
owner/deadline check，在读取 siginfo 后、SETOPTIONS 前和 SYSCALL resume 前再次
检查，修复此前操作跨越截止后仍可首次 resume 的缺口。独立 handshake 模型的
调用者仍负责自己的环境核验；真实 launcher 总是传入核验函数。
成功只交付独占的 `PostHandshakeDispatcher`，后续由已有 `BoundedWaitOwner`
接管；这不是 watchdog、全树 kill/reap、来源/输出认证或 COMPLETE 凭据。

13 项 synthetic 回归覆盖真实 tuple 路由、一次性转交、独占、idle/EINTR、剩余时间、
跨截止 stop 计费、错误身份、terminal-before-handshake、wait 错误、错误 tuple、
siginfo/options 期间超时、owner 变化和 uncertain resume。所有 fork/wait/ptrace
均为注入 fake；Linux JUnit bridge 在常规 Wrapper suite 实际运行，Windows skip
不能作为 Linux 验证。不执行 native probe、网络 clean-build 或历史 22 步。

#170A 存储分离和 #174 累计512次尝试/live128/2,000,000 wait-stop 的明确接受不变，
原时限、证据10MiB、失败停止和不退款语义不变。仍需 ordinary signal/vfork policy、
全树清理、冻结ABI探针身份、宿主/writer 与 fixture/task 预留表；整体最终 Review、
适用 CI、兼容性/inputhash 和唯一登记后才能实际执行原验收。
#183/#189关闭预算、#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/
runtime/default 停止门保持。生产代码、依赖和运行方式未改，无需修改 README/AGENTS。


## 本次新增修改：初始 wait 终态 PID 隔离

初始 wait 真实消费 terminal 后，即使握手尚未成功，内核 PID 也可能已释放。
此前 `accept_initial_wait` 拒绝 terminal，却未像 post-handshake/cleanup 路径记录
`unsettled_terminal_waits`，独立 `CleanupReaper` 因而仍可能再次等待该数字。
现在在任何 admission、owner/deadline、身份或 status 检查前复用原隔离方法；
包括错误目标和畸形 non-stop word，保留实际返回的 PID，不将它重标为 coordinator。
已有关闭 admission 仍保留可能已消费的 terminal；不清除原失败、不认证 reap、
不退 live/累计额度。正常 idle、EINTR、初始 SIGSTOP 不产生该标记。

新增7项 fake-only 回归（原13项共20项），覆盖 exited/signaled/core-dump、
跨 deadline/owner 变化、错误目标、畸形字、已关闭 admission，以及正常握手。
清理组合反例验证 `CleanupReaper` 在再次 wait 前拒绝该身份，保留原失败与 live
credit；不以 ECHILD 或隔离标记推断已回收。Linux 原有 JUnit bridge 强制30秒内
20项全部通过且零skip；Windows bridge仅编译/平台skip。

这修复的是身份隔离缺口，不提供未握手子进程的安全 pidfd/kill 能力，也不发出
COMPLETE 收据。未执行 native fork/wait/ptrace/pidfd/signal/probe、网络 clean-build
或历史22步。#170A/#174既有明确接受、全部预算和整体执行门不变。
生产代码、构建输入和冻结协议未改；README/AGENTS无需同步。


## 本次新增修改：一个可调用的 tracer 生命周期入口

`TracerSupervisor.run()`接受原可信host准备的`CoordinatorLaunch`，一次串联
`launch -> InitialWaitOwner -> PostHandshakeDispatcher -> BoundedWaitOwner.run`。
`InitialWaitOwner`现在将原可选`OwnedExecObservations`交给第一次且唯一的dispatcher claim；
之前该入口固定创建无配置dispatcher，ABI配置只能在已握手fixture内另行装配。
新入口不读取历史输入、不提供CLI、不自行构造探针/环境，也不在import/构造时加载native wire。

任一阶段失败会保留原异常与已建立的owner/ledger；若仍有live/pending责任，立即一次性调用
已有`SettledProcessCleanup.run()`，包括#241已接通的在途创建接管、pin/kill/真实reap。
cleanup仍用原绝对deadline、累计task/stop额度、失败锁存与terminal PID隔离，不退款、不重试。
无法清理的身份、已耗尽期限、未认证启动或close失败单独留在`cleanup_error`，原异常原样抛回。
正常返回只表示原账本drained，**不表示套件COMPLETE、filter能力或两个ABI全部验收通过**。

17项fake-only组合fixture覆盖完整launch/握手/两次EXEC/精确ENTRY/真实形状reap链，
以及普通失败后的自动清理、在途event不确定读取不重试、期限/启动失败、close失败与一次性。
Linux普通JUnit bridge要求17项/零skip、30秒；所有fork/wait/ptrace/pidfd/signal均注入。

有限剩余集成清单：

1. 本入口的原可信Java host调用、原来源/manifest冻结及完整filter/external no-injection checker。
2. host/tracer/coordinator三个互斥writer的同根10MiB额度传输与实际写前执行；
   `reserve_writers`仍只是分区，当前入口要求host提供已计费stdio，不伪称已实施此条件。
3. 复杂thread EXEC/leader提前退出/job-control清理、pin/binding失败及全部固定fixture/task预留表。
4. EXITKILL终末负例及整体最终revision独立Review/适用CI/原执行前门禁、compatibility/inputhash和唯一登记。

这些完成前不执行native probe、network clean-build或历史22步。整体原生验收尚未完成；
本次无研究结果或用户可见游戏功能。仅test-only调用关系变化，不改生产API/运行方式，
README/AGENTS无需同步；原170A/174已接受，预算/guard和停止门不变。

本次新增修改（PR review）：直接InitialWaitOwner也在claim前及每次wait前拒绝已占用/已停止的配置；无效配置不消费初始wait、共享stop或触发resume。覆盖构造前失效及构造后失效两组回归。
