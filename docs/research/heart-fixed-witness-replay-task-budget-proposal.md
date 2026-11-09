# 网络人工验收：累计任务预算修订候选

## 本次新增修改：明确接受与分阶段实施（2026-11-09）

用户在 Codex「查看开发进度」会话 `01a1bdd5-5d02-7580-95b4-42302a6fa60a`、
消息 `01a1be5a-b3ca-7302-a36e-cb291b66b584` 对 #174 明确答复「接受该具体修订」。
本文件的累计512次task创建尝试（包括线程及失败不退款）、live128、2,000,000次
wait-stop及创建前ptrace仲裁、clone3固定ENOSYS、清理与失败停止语义已获接受。
这是直接授权；下文“待接受/16仍有效”是原提案的历史状态，不是当前实施阻塞。
原900秒/阶段时限与证据10MiB不变；#170方案A已由 #191 实施存储分离。

第一项实现为 `scripts/fixed_witness_replay_tasks.py` 的纯内存 `TaskLedger`：
tracer初始槽、唯一协调器预留、逐创建尝试永久扣费、pending/live预留、
失败仅释放live、子事件不重复计费、确认reap释放live、出生ticket区分TID复用、
vfork子先reap及终态父reap清理pending、clone3拒绝尝试计数、wait-stop到界停止。
错误状态锁存后拒绝新增工作，但仍接收已预留的事件/结果及reap供清理。
live上限拒绝仍计已观察的尝试；累计上限拒绝不创建第513个槽。
事件计数到界即停止，该停点不得恢复。所有ticket均限定整数，未知状态失败关闭。

14项synthetic测试仅注入事件序列，不创建或跟踪任何task。小预算只用于纯账本
单元测试，不是人工探针可重置的子额度，也不执行原关闭的183/189验收清单。
Java Linux桥接将这些单元测试接入普通Wrapper CI；Windows直接Python执行。

**尚未具备创建前强制能力。** 账本不调用ptrace、不认证内核事件、不验证errno，
`clone3_enosys`只记录待适配的干预义务，不证明实际ENOSYS已注入。未来tracer
必须在恢复syscall前调用reserve，在事件/结果/终态reap时调用对应方法；
真实出生时间、syscall-info/ABI、CLONE_UNTRACED、clone3寄存器干预、线程exec/TID
重绑定、EXITKILL及64KiB报告分区仍待实施。当前不支持线程exec重绑定，不能接入
人工全链。全套固定fixture/task预留表须在整体实现最终Review前冻结；
整体最终Review/CI与唯一登记前不运行能力探针、完整network/clean-build或历史输入。
本组件没有修改生产架构，README/AGENTS无须调整。

### 本次新增修改：ptrace wire边界组件

`scripts/fixed_witness_replay_ptrace.py` 实现延迟加载的Linux x86-64 ctypes
传输边界。GET_SYSCALL_INFO以真实返回长度和operation解析入口/出口，不用奇偶位；
拒绝截断、未知operation和错误errno编码。compat/x32信息可被解析，但本组件不
授予放行例外。GETREGSET/SETREGSET只接受完整216字节原生寄存器布局。

`suppress_clone3`在核对原生入口及寄存器一致后，只将orig_rax改为无效syscall；
`finish_clone3`必须观察同一停点的真实Linux ENOSYS（38）和对应寄存器，才写入固定
ENOSYS并消费一次性token。不读取可被并发修改的clone_args，不提供任意errno或
网络结果改写接口。未来tracer必须先扣账本再调用，并在任何错误后停止并清理。

14项纯合成wire测试注入内核格式及fake ptrace回复，普通Linux Wrapper桥接执行
这些测试；不会调用native ptrace、启动tracee或执行能力探针。该接口没有attach、
fork、resume或wait-loop入口，尚未接入TaskLedger，也不证明clone3 fallback可用。
事件认证/出生绑定、CLONE_UNTRACED与其他创建标志仲裁、线程exec重绑定、EXITKILL、
报告分区和完整固定fixture/task表继续待实施。原整体Review/CI、唯一登记与执行
停止门均保留。该test-only组件不改变生产架构，README/AGENTS无须调整。

## 以下为原 PR 的历史提案

状态：**待审查、待用户明确接受；不是现行契约，不可执行。** 基线 main `24bb1770d77a1f186f5d896d469e828c21d285e4`；实现 Draft [#173](https://github.com/xuMingHai1/tetris-ai/pull/173) head `51c1fddb5ce37ee6ae689d6b27adecb1ba8b252f`。关联 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。本文件仅准备一个可批准或否决的具体替代设计，不修改已接受的[网络设计](heart-fixed-witness-replay-network.md)及任何冻结协议。现行16子进程限制继续有效。

## 问题、证据及不确定性

[#173 的静态展开](https://github.com/xuMingHai1/tetris-ai/pull/173#issuecomment-6614817947)已经区分：最小 prepare→pipe 成功链6次 source_identity，每次5个Git leader，共30次；保留现有桥接额外 verify 则35次。原网络组件12个leader及2个fork，加30次Git、3个prepare leader及1个launcher/JVM，至少48次进程创建；尚不含协调器、checkout设置及Wrapper内部后代。该数值不是完整观测，也不是新预算。

现有 `run_process` 的进程组清理与deadline不能在fork之前约束累计创建次数；轮询 `/proc` 会漏掉短命进程。只检查Python的Popen次数也会遗漏Git/Wrapper/JVM后代。网络组件通过不意味着完整验收可执行。

Wrapper和JVM实际线程/进程总数、ptrace对Java启动/ACK时序的影响、runner所允许的ptrace能力目前未知。本候选不通过额外运行来测上限，不把未知量估为零。

## 方案比较及推荐

| 方案 | 覆盖与代价 | 结论 |
| --- | --- | --- |
| 原16不变；合并Python测试、shell包装或缓存identity | 包装不消除fork；缓存会改变前后字节认证；尚无包含Wrapper且保留全部检查的可行证明 | 保留为不修订时的停止路径，不声称可交付全链 |
| 仅扩大Python Popen计数、RLIMIT_NPROC或cgroup pids.max | Popen遗漏后代；后两者主要限制同时存活数量，进程退出会释放额度，不能保证累计次数 | 不作为完整累计硬界 |
| Linux原生x86-64 ptrace syscall入口仲裁，累计任务预留 | 可在内核创建任务前停止每个受管入口，覆盖Git/Wrapper/native fork；实现和兼容性成本较高 | 推荐作为独立test-only实现候选，能力不足立即停止 |
| 容器/外部特权服务 | 仍需累计仲裁，另增身份、权限及PID映射边界 | 本轮不采用，不自动切换 |

推荐理由是可对完整后代树逐次预留，而不是认定ptrace已可用或512足够。本候选只覆盖受审查的合作式进程树，不提供恶意本机攻击者沙箱。接受设计不会免除原网络seccomp的独立验证。

## 可供批准的有限预算

以下数值是一次验收的**资源政策上限**，不是实测需求、性能预测或“足够完成”的证明。建议以更严格的Linux task计费替换人工套件的16子进程计数；原文的16不被本草案自动覆盖。

| 项目 | 候选硬界及计费 |
| --- | --- |
| 累计任务额度 | **512**；包含最外层专用tracer本身1槽、其直接协调器1槽，所有fork/vfork/clone/clone3创建尝试均在入口永久扣1槽；线程也扣槽，不因失败或退出退款 |
| 同时存活任务 | **128**（包括tracer）；在可能创建任务前预留live槽，已确认reap才释放live槽；累计额度不释放 |
| trace事件 | **2,000,000次wait-stop事件**，syscall入口/出口、signal、fork/exec事件全部计数；事件超限停止，不能改成抽样 |
| 全套墙钟 | 原**900秒**，从可信宿主启动专用tracer之前起计，包含checkout/setup、所有探针、prepare、build、JVM、认证、报告及清理 |
| 阶段 | 原build≤600秒、Java窗口≤120秒（含launcher）、父监督≤180秒；native/Python单probe≤5秒，原组件绝对50秒不重置 |
| 输出及存储 | 原累计10MiB不变；tracer报告在此root中预留**64KiB**（tracer32KiB+宿主32KiB），包括错误前缀；不得另开不计费trace日志。构建目录归类仍受PR #170独立决定约束 |
| Java | 原heap256MiB/metaspace128MiB；不得额外并行第二个构建/JVM；RSS未知仍报告未知 |

512包含线程，因而比“512进程另加无限线程”严格；128 live是第二道并发边界。48的静态下界仅说明16冲突，不推导512。选择512/128/事件界作为可被用户拒绝的一次有限尝试，不因耗尽追加、切环境或重新标定。即使只差一个槽、或ptrace导致900秒超时，也记录INCOMPLETE并停止该验收；不得自动升至1024或删探针。

## 执行前强制机制

实现限定为Python stdlib/ctypes Linux x86-64专用tracer与人工协调器，无pip、无root、无宿主配置修改。它是待实现的独立模块，不把现有process helper宣称为已经具备这些能力。

1. 现有Java人工桥接为可信启动/终态观察宿主，不属于受管树；它在启动tracer前创建不可复用session目录、启动900秒总时钟、初始化root及固定报告预留并扣tracer自身槽。宿主只做可信启动、计时、终态观察和后缀写入，其工作耗时也在同一总时限内；不将整个既存Surefire JVM或上层Maven算为新创建task。tracer接收已消耗的时间/额度，不重置时钟；宿主始终保留同一更早的硬截止并限制启动延迟。不得在开始计时计费前clone/checkout或启动能力探针。上层普通Maven/Surefire只是调用测试的宿主，不属于套件；验收边界明确从宿主为专用tracer预留槽和启动计时开始，边界内的第二层Wrapper及全部后代必须计费。宿主不能代做套件准备来规避计费。
2. tracer在唯一线程中先扣协调器槽再fork。该子在exec协调器前执行TRACEME并SIGSTOP；tracer安装TRACESYSGOOD、TRACEFORK/VFORK/CLONE/EXEC及EXITKILL后才放行。初始fork是唯一由可信tracer直接执行的fork；它的预留在创建之前，子在停止握手完成前不执行用户命令。tracer不再创建其他worker或用shell收集证据。
3. 对所有受管task使用SYSCALL入口/出口停点，按内核提供的syscall-info/ABI区分signal/event与入口出口，不能靠奇偶位猜测。在入口放行fork/vfork/clone前，串行永久扣累计槽、预留live槽并保存pending记录；任何额度不足则**不恢复该创建syscall**，进入全树失败清理。多线程并发入口仍由同一tracer逐个仲裁。
4. 原生clone的CLONE_UNTRACED必须拒绝；任何能取消跟踪或改变受管关系的未知标志组合不得放行。clone3带可被其他线程修改的用户内存结构，不能“读一次flags后就信任”。本候选固定将clone3在入口改为无效syscall，并在出口返回ENOSYS，依赖标准库的legacy clone fallback；被拦截的clone3仍永久扣累计槽，但不保留live槽。必须保存此ABI兼容干预，不能称完全透明。无fallback即CAPABILITY_UNAVAILABLE，不能自动允许clone3或修改JVM参数挽救。
5. 所有已放行的创建事件必须与一次pending预留对应。fork/clone事件只关联新TID，不再次扣累计槽；内核失败释放live预留但不退累计槽。vfork在父等待子exec时，仍继续监督子事件，不能等待父syscall出口造成死锁。线程exec引发TID身份变化须按EXEC事件重新绑定，不丢弃账本。PID重用使用出生序列/事件绑定；未知子、缺事件或无法对应的exec一律停止，不标为完整。
6. 不依赖libc包装，拒绝未知创建入口；兼容ABI/x32仅有一个冻结例外：原组件两条已绑定ABI探针的固定非创建syscall入口须不改写地放行，让真实seccomp产生原预期SIGSYS。绑定探针字节/argv、ABI及原syscall号，不能只按进程名字放行或用tracer发SIGSYS冒充过滤器。其余非原生入口均停止，ABI例外绝不能创建任务。跟踪全部后代，包括setsid后的受管task。控制器不主动DETACH；禁止受管进程通过ptrace改变跟踪关系。seccomp仍由原launcher安装并由原父认证检查；tracer不得改网络syscall返回值来伪造EPERM、READY或ACK。仅clone3执行上述固定变换，其他syscall保持参数/返回不变。完整账本包含干预次数。
7. trace事件达到上限、输出quota/deadline、取消、未知ABI/状态、追踪丢失均禁止ACK或后续阶段启动。杀死所有已知受管TID并reap，保留INCOMPLETE前缀；EXITKILL约束tracer意外死亡后的tracee，现有进程组清理也保留。退出仍有未reap任务时不得报告完整；清理耗时计入原总deadline及已有清理预留，不增加等待窗口。
8. 每次验收只运行一个固定清单：checkout/setup→原组件（不删反例）→prepare→隔离verify-only→finalize→汇总。完整前后identity校验原样执行；不得将identity移到未受管宿主、删除额外检查而隐去差异，或让构建启动独立脱管服务。失败立即停止，不再进入下个阶段。

**风险：** ptrace会改变时序，ENOSYS fallback可能不兼容；runner可能禁止跟踪，Java可能产生过多线程/事件。即使全部通过，也只有该受管人工路径的能力结果，不提供桌面延迟、恶意逃逸防护或历史诊断采用证明。

## 实现、验收与停止门

本设计准备不执行任何探针。若用户接受本修订、最终设计独立Review/适用CI及正常合并完成，才允许实现专用tracer和人工桥接。实现PR仍先接受独立最终Review/CI，再登记一次完整人工验收；在PR #170未解决时不启动完整clean-build能力套件。不能把设计CI内普通现有测试提升为该次验收。

实现必须覆盖下列人工反例，所有后续验收进程都受相同512/128/事件/时间/root限制；固定清单与对应task预留表在实现最终Review前提交，不能运行后选择有利子集：

- 连续短命fork累计到界；小额子预算k的第k+1次被阻止且没有子marker。另测内核fork失败后不退款、live槽reap前不释放。
- 并发线程竞争最后一个槽、vfork→exec、线程exec/TID变化、leader提前退出、setsid后代仍被跟踪与清理；每个创建事件恰有预留且无重复计费。
- 原生直接syscall、CLONE_UNTRACED、clone3固定ENOSYS及fallback、兼容ABI/x32不能绕过；未知事件、账本丢失或不可读寄存器禁止继续。
- deadline/trace事件/输出耗尽均保留有界失败前缀，所有cleanup在原时限内；marker不能在超限拒绝后产生。tracer死亡的EXITKILL负例必须按下述终态宿主方案执行，不能用嵌套ptrace争抢同一task。
- 原网络完整反例不削减；真实隔离普通JVM的原PID/argv/receipt/代码/库/hash/nonce/ACK-EOF/VERIFIED_NO_REPLAY必须通过。ptrace模式不是网络拒绝证据。
- checkout、30/35次inventory、Wrapper/shell/JDK后代与线程必须出现在统一账本。与静态预留清单不一致即实现无效，不能只看最终总数≤512。不得使用历史22步、seed、population或公网请求。

人工反例中的“小额度耗尽测试”使用从同一root永久预留的子task额度，不为每个测试重置512；需513次循环的边界验证以较小子额度替代，断言父账本超限拒绝逻辑不依赖512的具体值。禁止为了测512再额外启动513个进程。实际总额度512不可退款，不创建第二套隐藏预算。

### 唯一tracer死亡的终态负例

这项必测安排在全套最后，不在同一树内再建立第二个ptrace tracer。可信Java桥接宿主仅使用现有进程句柄发送信号、Java文件API读取/proc及预留报告槽，不启动观察shell/Python、代做inventory或构建。专用tracer从出生到死亡仍计入1槽，所有测试子任务仍由它预留和监督。

完成其他必测后，tracer保留一个已绑定的无输出哨兵task，先令其进入已认证的ptrace stop（不再恢复），确认其他tracee全部reap且没有pending创建，再写出有界ARMED_NEGATIVE receipt：最终累计计数、已reap清单、唯一仍存活PID/TID及其/proc出生时间、父子关系、策略/hash和nonce，声明不再放行任何创建入口。receipt先fsync并保持INCOMPLETE/ARMED状态。宿主先校验receipt与实时/proc列表一致（只允许tracer及哨兵存活；出现其他task即拒绝），将receipt hash记入预留报告，再只杀tracer。哨兵停在已确认的ptrace stop，不执行创建/exec/写入或主动退出；宿主必须在原清理预留与900秒总截止内观察其被EXITKILL终止，使用出生时间区分PID复用；僵尸无执行能力但未消失/未reap仍不能报完整清理。

跨进程不得复制一个可重复消费的Python ByteQuota对象。宿主启动前从10MiB永久划分tracer32KiB、宿主32KiB、协调器10MiB-64KiB三个互斥writer额度；每个拥有者仅在自己的额度内再预留，不退款。tracer不能使用宿主32KiB。宿主的报告、日志转录、固定摘要及失败信息全部从自身32KiB扣除，禁止转印整份trace或异常对象；现有Maven/Surefire物理构建文件的分类仍受PR #170约束，未明确前不执行全套，不声称当前root已硬约束所有构建写入。只有receipt核验、全部绑定task清理与既有各项结果均通过，宿主才写固定EXITKILL_NEGATIVE_VERIFIED及人工套件成功摘要。ARMED_NEGATIVE本身不是COMPLETE；未产生receipt、tracer提前死、哨兵自行退出、账本不一致或清理不完整全部INCOMPLETE，保留原prefix，不补跑。专用tracer使用现有stdin控制握手在宿主确认后才进入最终等待；宿主不得把主动杀哨兵当EXITKILL成功，紧急清理与能力验证分开记载。该terminal负例的receipt/root哈希及宿主后缀均由最终报告checker独立核验。

分类：宿主ptrace/ABI/clone fallback不满足为CAPABILITY_UNAVAILABLE；账本漏项、错放行、源/hash/握手变化为IMPLEMENTATION_INVALID；资源耗尽或清理未完成为INCOMPLETE。三者均不是研究BOUNDED_MISS，不授予重跑/加预算；普通实现错误允许修复后按独立Review流程验证，不能重启已登记完整验收来调参。

## 需要用户接受的精确变更及交接

请求仅限：是否接受将[网络设计](heart-fixed-witness-replay-network.md)中**人工套件16子进程**条款替换为本候选的**累计512次task尝试（含线程、失败不退）、live128、2,000,000 trace事件**，及上述执行前ptrace仲裁/clone3 ENOSYS/失败停止语义。用户可以拒绝并继续保留16；不请求泛化“允许以后改协议”的空白授权。

即使接受，也不改变原10MiB、阶段和总时限、原guard/历史协议、PR #170存储分类、唯一执行登记；不重跑#140/GoalWindow，不解锁#95/#96、reserved/provider/runtime/default。本文不修改生产架构，README/AGENTS无需变化；本次仅一个研究设计文件，不新增实现、workflow或fixture。当前仍待审查及明确接受，**不得因Draft或CI绿色自动合并或执行。**
