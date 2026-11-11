# 已认证独立进程的 pidfd 终止与回收

本组件实施已接受 #174 中的失败清理子路径；#170A 存储分类、累计512次创建尝试、
同时128存活、2,000,000次wait-stop、原时限及证据10MiB均不变。

## 身份与源码依据

`SettledProcessCleanup.run` 组合既有 `CleanupReaper`，先永久撤销正常监督，
通常接受已通过真实初始握手、dispatcher仍保有birth绑定、无未绑定子birth的creation、
每个group_birth只有一个存活birth且TID等于原TGID的库存。逐一取得pidfd后，
才向全部已固定描述符发送SIGKILL，最后进入原有正TID终态回收。
不从 `cleanup_tid` 推断身份，不调用kill(pid)、wait(-1)、ptrace resume或信号注入。

Linux v6.17 [`kernel/signal.c` 2232–2253](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L2232)
的SIGCHLD忽略/SA_NOCLDWAIT自动回收分支明确要求 `!tsk->ptrace`。
已绑定、未被唯一tracer回收的单进程birth因此不能在pidfd取得前被该分支释放PID；
没有预先保留startup pidfd的未握手launch仍拒绝：只有fork返回数字的子进程可能在
TRACEME前死亡并自动回收。下述新增路径在fork前排除自动回收，立即保留同一child的pidfd。
同时拒绝多存活线程和未绑定创建，避免非leader EXEC静默退休或未知子任务破坏该证明。
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
没有startup pin的未握手launch、未绑定创建/线程EXEC清理及ptrace事件drain仍拒绝；
不是完整监督器或COMPLETE收据。可信宿主/writer、冻结ABI身份与完整fixture仍待实施，
原整体最终Review/CI、compatibility/inputhash与唯一登记门继续有效。

## 本次新增修改：预握手 coordinator 的 retained pidfd 清理

`CoordinatorLaunch` 的真实OS构造路径在fork前加载 `CoordinatorPin` transport，
`launch()` 在原owner/deadline及创建预留前，通过Linux x86-64 `rt_sigaction(13)`
只读查询SIGCHLD。使用内核32字节action布局和8字节sigset参数，不使用libc布局，
不设置handler、mask或任何宿主配置。只接受SIG_DFL且无SA_NOCLDWAIT；SIG_IGN、
自定义handler、查询失败均在fork前停止。独占单线程host仍必须禁止其他waiter、
signal回调reap及SIGCHLD disposition变更；本实现不抵抗恶意同进程代码。

fork返回有效PID后，父侧在ledger/dispatcher/handshake等可失败初始化前立即
`pidfd_open(pid, 0)`。前述非自动回收及sole-wait前提保留已退出child的zombie，
因此这里使用的是仍未reap的原始fork child。pidfd获取失败不重试、不退款，也不
给后续数字PID lookup权限。pin及尝试的PID分别保留；账本binding自身失败仍是
INCOMPLETE，保留pin供释放，但不猜测birth或伪造reap。

`SettledProcessCleanup` 仅在同一launch保有该未转交pin、精确单coordinator live birth、
无pending、无terminal隔离时接纳预握手路径。它一次转移原FD，绝不再次pidfd_open，
随后复用原pidfd-SIGKILL、同deadline正TID真实reap和共享stop计费。pin原拥有者的
close负责最终释放；未知结果不重试。正常握手成功后直接关闭startup pin，转回
已有authenticated cleanup。独立 `CleanupReaper` 认证terminal后也释放pin；
失败initial wait已消费terminal则隔离PID并关闭pin，不给kill或第二次wait权限。
关闭异常保留且不覆盖该initial admission原异常。仅真实reap释放live credit。

新增16项fake-only组合测试及30秒Linux普通JUnit桥，要求16项全部通过且零skip。
覆盖SIGCHLD反例、查询/期限/fork/open失败、初始化与wait/options/resume失败、
成功握手、terminal隔离、原stop计费、close不确定及只读内核ABI参数。所有native
查询、fork、pidfd、kill/wait均注入；未执行原生探针、network clean-build或历史输入。

### 整体有限剩余集成清单

1. 唯一wait的EXEC→ENTRY/delivery→真实reap接线由#236单独交付；核对其最终合并，
   不把模型`TERMINAL_FACT_MATCHED`升级为filter能力证明。
2. 冻结source/filter安装及no-injection checker，关联同birth/exec/manifest与原样resume；
   本次新增原wait receipt→精确ENTRY/delivery resume→真实reap的归属核验；
   本次将冻结network chain的launcher/probe两次EXEC观察接入同birth相邻代次，
   核对原始argv、源码/解释器/libseccomp字节、filter基数与安装后状态，再进入已有
   ENTRY/delivery/reap链。可信host原始冻结来源、安装过程的完整checker及全部
   no-injection约束仍缺；两次proc观察不提供filter字节证明。
3. cleanup还缺线程EXEC/leader提前退出与复杂事件、job-control处理；本次只解决已绑定
   coordinator的预握手pin路径，ledger binding失败或无法取得pin仍INCOMPLETE。
4. 可信Java宿主与tracer/coordinator互斥writer传输、全套冻结fixture/task预留表及终末
   EXITKILL负例。`reserve_writers`目前只是分区组件，尚非宿主入口。

上述路径接成实际host入口、完整最终revision独立Review/适用CI/原执行前门禁、
compatibility/inputhash和唯一登记齐备后才执行原批准有界验收。整体里程碑仍未完成；
研究结果和用户可见游戏功能本次无新增。未改生产API/玩法/依赖，README/AGENTS无需同步。

## 本次新增修改：事件已绑定、父结果未到达的进程创建

`TaskLedger.cleanup_pending()`返回失败后仍可读取的不可变预留快照，包含parent、
reservation、kind、thread及child birth；它不结算syscall、不退款，也不授权resume。
`CleanupReaper._inventory()`仅新增接受每条pending都已有真实创建事件绑定child的
进程路径；子birth必须属于自己的group且保有dispatcher绑定，或已有认证reap。
原有单live birth/group、原TGID、terminal隔离、独占owner与原deadline检查继续适用。
clone3、缺创建事件、线程、未知child及未完成EXEC身份仍拒绝，不猜测内核结果。

Linux v6.17 [`kernel/fork.c:2367–2374`](https://github.com/torvalds/linux/blob/v6.17/kernel/fork.c#L2367)
在公开新任务前执行`ptrace_init_task`；
[`2636–2648`](https://github.com/torvalds/linux/blob/v6.17/kernel/fork.c#L2636)
在唤醒child后报告创建事件，随后才等待vfork完成并返回父syscall结果。
因此已认证event-bound child即使尚未完成newborn stop/wire初始化，也已在该tracer下；
结合前述未被唯一owner回收的PID不释放依据，可以沿用全部pidfd先固定、再全部SIGKILL
的原路径。不能把此依据推广到尚未读取/绑定GETEVENTMSG的创建或未握手coordinator。

父先reap由既有`CreationLifecycle.reaped`关闭该父的pending，child独立保留live slot；
子先reap仍保留pending直到父真实reap。两者均不伪造成功/失败syscall result，累计attempt
从不减少。子已被认证reap则不再为该PID打开pidfd。嵌套的全部pending必须同样满足条件，
任何一条未绑定即在第一次pin前拒绝。正常dispatch失败锁存不会清除。

新增15项fake-only组合行为测试及Linux普通JUnit bridge（30秒上限、要求15项/零skip），
覆盖父/子先reap、vfork及clone进程、wire/parent resume失败、嵌套pending、未绑定/线程/
clone3拒绝、部分pin失败、newborn排队stop和terminal消费跨deadline后的隔离。
Windows只提供bridge编译/平台skip；未运行任何原生ptrace/pidfd/probe或历史输入。
这里只扩展已知进程birth库存，不是完整整树清理，生产API/运行方式和README/AGENTS不变。

## 本次新增修改：失败后读取在途进程创建事件

`CleanupReaper.bind_creation_events()`是显式的一次性清理阶段，由同一个独占wait owner
在`SettledProcessCleanup.run()`取得任何pidfd前调用。它只接受已有native入口、dispatcher
pending及lifecycle obligation一致的fork/vfork/legacy process clone预留；先检查整批
已知parent的原birth/TGID和独占wire，再用原`WNOHANG|__WALL|__WNOTHREAD`轮询正parent TID。
没有事件时最多睡10ms，仍受原deadline约束；不resume来制造事件、不建立新预留或额度。

实际wait得到原obligation预期的FORK/VFORK事件后，先扣共享stop，再读取该parent wire的
GETEVENTMSG。`CreationSyscallDispatcher.cleanup_child_event()`在失败状态下只绑定原预留
的child，不经过正常`_running`路径、不清除任何失败。child ledger/dispatcher birth在
fallible wire初始化前保留；初始化失败则本阶段停止，不丢失已出生任务。GETEVENTMSG后
遇到owner/deadline失败时，保留`last_creation_event`原事实，不能当作已认证birth继续pin。

源码依据为Linux v6.17
[`kernel/fork.c:2636–2648`](https://github.com/torvalds/linux/blob/v6.17/kernel/fork.c#L2636)
的创建事件/父返回顺序，及
[`include/linux/ptrace.h:160–184`](https://github.com/torvalds/linux/blob/v6.17/include/linux/ptrace.h#L160)
规定GETEVENTMSG使用ptracer namespace中的child PID。仍要求原同PID namespace、唯一
owner/没有外部ptracer的可信host条件，静态依据不是宿主能力结果。

正常StopReader和PostHandshakeDispatcher始终保持失败锁存；本阶段不设置options、
不写register、不读取syscall-info、不注入信号、不消费parent result。完成绑定后才能沿用
已有全库存pin/SIGKILL/真实reap路径。非预期stop、EXEC、线程、clone3、未知/重复child、
ECHILD或terminal均失败，不猜测不存在/已回收。消费terminal时先记录原PID隔离标记；
只有既有认证reap能释放live credit。失败后不重试本阶段，也不继续pin或执行正常工作。

新增17项fake-only测试和Linux普通JUnit bridge（30秒上限、17项零skip），覆盖失败后
FORK/VFORK/clone完整绑定到kill/reap、原预算/失败保持、idle/EINTR、错误事件/身份、
stop耗尽、期限/owner变化、wire失败保留、ECHILD与terminal隔离、畸形结果和一次性。
原已绑定pending测试保持独立。该组件最初不自动调用本阶段；正常owner已消费事件的接管
见下方新增记录。线程EXEC、未握手身份和复杂ptrace-event drain仍待实施。
未执行原生fork/ptrace/pidfd/signal/probe、网络clean-build或历史22步。

## 本次新增修改：接管正常 wait owner 已消费的创建事件

`BoundedWaitOwner`在实际定向wait前保存parent birth和未绑定预留快照，获得匹配结果后、
进入`PostHandshakeDispatcher`前保存内部`_OwnedWait`记录。正常`StopReader`仍先扣共享
stop，再检查owner/deadline及读取内核。`_StopAccess`将GETEVENTMSG的尝试标记、成功值
存到该记录；读取抛错时保留不确定状态，不能以清理名义重试。直接`accept()`和调用方的
raw tuple/StopFacts都不会产生该来源记录；此机制仍以唯一可信host/waiter为前提。

失败后显式`bind_creation_events()`先检查注册的原正常wait owner、失败状态、原deadline、
完整parent identity、同一reservation及原FORK/VFORK映射，再一次性接管匹配记录。
已成功读取GETEVENTMSG时复用该值，不进行第二次wait、GETEVENTMSG或stop扣费；
已扣费但尚未尝试读取时，只允许清理owner读取一次。不确定读取、非创建事件、身份或
预留变化、重复接管都失败关闭。没有记录的预留继续沿用既有实际新事件等待阶段。
已绑定child不会重复接管，wire初始化失败仍保留birth，所有原失败和累计/live预算不变。
原deadline已到时只保留证据，不延长时间来完成接管。

16项新增fake-only行为测试及Linux普通JUnit bridge（30秒、要求16项零skip）覆盖上述
接管到pin/kill/reap，以及vfork/legacy clone映射、原owner恢复、预算耗尽、跨deadline、
不确定读取、直接调用拒绝、身份/预留变化、重复child和wire失败。仅合成验证；未运行
原生任务、ptrace/pidfd/signal/probe、网络clean-build或历史输入。生产/冻结协议未变，
完整host接线与复杂整树清理仍未完成，原整体验收和停止门继续保持。

## 本次新增修改：单一清理入口串联在途创建与真实 reap

`SettledProcessCleanup.run()`现在在第一次pidfd acquisition前检查原ledger的pending。
若存在未绑定child且原reaper尚未尝试binding，直接调用同一reaper的
`bind_creation_events()`：优先接管原正常wait owner已消费的事件，否则沿用原deadline、
正parent TID和共享stop计费等待已预留的fork/vfork/legacy process clone事件。
绑定完成才进入完整库存校验、全部pin、全部SIGKILL与原真实reap。
调用方不再需要手动编排两个公共入口；无pending和全部已绑定库存不增加wait或事件读取。

手工已完成binding的调用仍兼容；已失败或不确定的binding不会自动重试。任何binding失败、
预算耗尽、期限/owner变化或unsupported reservation均在第一次pin前终止，保留原失败、
已读事件、已绑定birth及terminal PID隔离。只有真实reap释放live credit，attempt从不退款。
这一接线不扩大既有binding与库存的身份准入，线程EXEC/leader提前退出、复杂ptrace事件及
job-control整树清理仍缺。原可信host、filter/no-injection checker、同根互斥writer和完整
frozen fixture及最终整体门禁仍未完成；不能据此运行native/network clean-build/历史输入。

12项新增fake-only组合测试覆盖单run的事件到kill/reap路径、vfork/clone映射、已消费事件
接管、uncertain读取、binding wire失败、terminal隔离、原期限与stop预算、手动兼容和
unsupported整批拒绝；Linux普通JUnit bridge要求30秒内12项/零skip。旧测试同步核验
失败前允许的有界定向wait，但仍禁止pin/signal，而非继续断言入口完全不触及wait。
这只是清理调用链的合成验证，不是整体原生验收、研究结果或用户可见功能。生产API、
冻结协议、预算和运行方式不变，README/AGENTS无需同步。
