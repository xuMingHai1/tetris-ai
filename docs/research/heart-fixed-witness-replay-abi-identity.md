# 固定 ABI 反例的出生身份与一次性准入设计

本设计细化已明确接受的 [#174 第6条](heart-fixed-witness-replay-task-budget-proposal.md)，
不增加 ABI 例外、探针、原生执行次数或预算。纯匹配/消费、只读union解析、delivery/终态事实匹配与
有界exec/proc观察、唯一wait owner的EXEC代次/停点门与结算后模型终态接线子集已实现；
唯一wait owner下的精确ENTRY及delivery接线也已实现；来源/filter/no-injection完整checker、
host证据writer及整套fixture仍未接通，不能执行原生probe/network clean-build或历史输入。
普通creation dispatcher的native guard不变；只有已认证绑定的两个固定ENTRY可走内部接线。
本次新增修改不改变生产架构，README/AGENTS无需同步。
源码基线：`993f6ad4caf4439bfffd4694c68bc2bbf4c4105c`（已纳入 #218 独立清理组件）。

## 问题与源码证据

`CreationSyscallDispatcher.syscall_info_stop`要求原生 syscall-info；原
`test_fixed_witness_replay_network.py` 的 `probe('x32')` 调用 `0x40000029`
（x32 socket），`probe('compat')`执行 `b866000000cd80c3`（i386 socketcall）。
当前直接组合会先在 tracer 拒绝，不能把这种拒绝误报成 seccomp 的 SIGSYS。

Linux v6.17 [`kernel/entry/syscall-common.c:35–46`](https://github.com/torvalds/linux/blob/v6.17/kernel/entry/syscall-common.c#L35)
先 `ptrace_report_syscall_entry`，然后 `__secure_computing`，并明确说明此顺序用于捕获
tracer 的修改。因此例外必须放行原入口，而非跳过调用、改返回值或主动发送 SIGSYS。
[`syscall_64.tbl:53`](https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_64.tbl#L53)
和 [`syscall_32.tbl:117`](https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_32.tbl#L117)
分别绑定 socket 41 和 socketcall 102，均非创建入口；i386 fork/clone/vfork分别为2/120/190。
[`kernel/seccomp.c:1339–1354`](https://github.com/torvalds/linux/blob/v6.17/kernel/seccomp.c#L1339)
的 KILL 路径终止任务；不得要求一个普通 syscall EXIT 才确认终止。
同版本[`kernel/signal.c:1811–1823`](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L1811)
的`force_sig_seccomp`填写`SYS_SECCOMP`、`si_arch`、`si_syscall`；仅信号号码不证明来源。
这些是静态依据，不是目标宿主能力结果。实际内核不能提供所需事实时停止，不切 ABI。

## 路径选择与范围

- 按 mode、进程名字或单独 syscall 号放行：不能区分其他 task 或复用 PID，排除。
- 为全部 compat/x32 建立通用创建仲裁：超出两条固定例外，排除。
- 推荐：固定源码/精确 argv 清单，加已认证 exec 的不可复用 birth，一次性入口票据；
  两条之外仍由现有 native guard 拒绝。实现分为纯匹配模型和真实认证接线；
  前者的成功不能独立授权 resume，后者未完成前不接通例外。

仅新增 test-only 组件及行为测试，必要修改现有 dispatch/stop/wait 接线；不改变网络
filter、拒绝表、clone3 ENOSYS、生产 API 或默认玩法。不提取或运行历史22步。

## 冻结清单与可信输入

执行前在同一 source/manifest 认证链冻结两条记录；每条包含模式、完整源码文件字节
SHA-256、canonical Python executable 路径/字节SHA-256、完整精确 argv 和允许的
ENTRY `(audit_arch, syscall_number)`。源码字节包括上述探针调用/机器码；不能只 hash
函数名字或反例标签。launcher/libseccomp/policy 继续使用原独立绑定，不被此记录替代。
预期值由冻结 checkout 和父侧可信 manifest 生成，不能用 tracee 自报值作为预期值。

| 模式 | 精确目标 argv 尾部 | ENTRY arch | 原 syscall 号 | 使用次数 |
| --- | --- | --- | --- | --- |
| x32 | `-I -B <canonical test_fixed_witness_replay_network.py> x32` | `0xc000003e` | `0x40000029` | 1 |
| compat | `-I -B <canonical test_fixed_witness_replay_network.py> compat` | `0x40000003` | `102` | 1 |

argv第一项必须是已绑定Python；表中的路径占位符在登记前变成精确绝对路径，不做basename、
前缀或可选参数匹配。完整 argv 既比较长度也比较每一项。源码/解释器 hash或路径变化立即
拒绝，不能在运行中重新冻结。路径归一化不能替代字节校验。

唯一 tracer 在真实创建事件和 EXEC 迁移完成后，将该预留 probe 槽与当前 live birth、
group_birth、TID及exec代次绑定，再从该停点读取真实 `/proc` exe/cmdline 与文件字节，
与冻结记录比较；不启动 shell/Git/辅助观察进程。每个模式只允许绑定一次，binding不是
可转交给其他birth的字符串。exec entry/event本身仍走native认证，无例外。

文件/argv认证是本项目协作式进程模型中的身份依据，不证明Python实际执行了哪些指令，
也不是恶意tracee或恶意宿主的内存代码证明。可信host须保持冻结文件不被并发修改、
所有wait/ptrace由唯一owner持有；无法建立此前提则拒绝准入。不得把纯模型传入的hash
或`authenticated=True`当成真实认证。

## 一次性状态与调度契约

1. `UNBOUND -> BOUND`：只有上述认证及原owner/deadline/预算检查全部完成，才绑定
   清单中的预留槽。失败锁存原session；不新建预算，不增加task，不退款或重新绑定。
2. `BOUND`期间普通native入口/出口仍走现有完整创建仲裁和sequence校验。
   收到非原生ENTRY时，先经StopReader扣共享stop额度，再核对当前birth/TID/exec代次、
   清单hash、精确arch/number和未消费状态。任何错配均停止。创建syscall即使来自probe
   也不能走例外；不能根据32位截断、掩掉x32 bit或猜测ABI来比较。
3. 完全匹配时先原子消费票据为`AWAITING_SIGSYS`，再仅执行原来的SYSCALL resume，
   signal=0。不写register、不改syscall/errno、不补发signal。恢复结果不确定则失败，
   票据仍已消费，不能重试。外层执行这一步仍必须复核原owner/deadline。
4. `AWAITING_SIGSYS`不允许任何后续syscall ENTRY/EXIT、创建或EXEC，不把它交回普通
   native sequence（后者仍持有特殊入口状态）。允许现有认证信号处理路径保留真实
   SIGSYS的原siginfo并原信号恢复，但观测到delivery时必须验证`si_code=SYS_SECCOMP(1)`、
   `si_arch`和`si_syscall`均匹配已消费入口。SI_USER/SI_TKILL及未知code直接使本反例
   失败，不能经普通信号路径形成成功链。`SignalInfo`已提供固定Linux布局的只读
   SIGSYS union字段；未来本反例身份接线仍须验证，不能用sender_pid字段替代。
   不能要求必然出现delivery stop，不能主动注入SIGSYS。其他普通信号不被解释为成功；
   按既有信号策略处理但不释放票据/额度，不延长截止。
5. 唯一owner真实wait并认证该birth的`signaled(SIGSYS)`后，先正常reap结算，再标为
   `KERNEL_TERMINAL_OBSERVED`。不要求EXIT；exit(0)、其他终止信号、ECHILD、伪造wait、
   丢失birth或清理主动SIGKILL均不能成功。仅该终态也不能独自证明过滤器：最终checker
   必须同时绑定原filter安装/来源链、entry原样放行记录、无注入记录和清单hash。

BOUND内native创建继续按原政策仲裁，子birth不继承ABI票据；BOUND再次EXEC立即撤销
绑定且该模式槽保持已使用，不重新绑定到新exec或转移给其他birth。AWAITING_SIGSYS后
任何创建/EXEC均失败。已存在子任务仍按原账本清理，不推断reaped。
状态改变不能清除任何既有失败锁存。失败后normal dispatch不再可用，使用独立cleanup。
ledger的正常live释放只来自真实reap；两条例外不跳过任何task或stop计费。

## 有限实施验证与停止条件

实现PR先用假transport覆盖以下12组行为，每组均可含有限表驱动子例；不加载libc、
不fork/ptrace/mmap执行或触发原KernelTest：

1. 两条精确ENTRY可各消费一次，输出事实不携带独立resume权限。
2. mode/argv项数、参数、路径、源码与解释器hash各自错配时不准入。
3. 未绑定、其他birth/TID/group_birth、旧exec代次与复用PID拒绝。
4. 第二次绑定、第二次ENTRY及跨模式票据复用拒绝。
5. compat/x32的fork/clone/vfork/clone3、未知ABI/号码全部拒绝。
6. 非ENTRY、截断syscall-info、类型和整数宽度错误拒绝。
7. BOUND内正常native创建仍预留且按原失败/live规则结算，不走例外。
8. 例外恢复失败、owner/deadline改变时不重试，不退款，不写register。
9. 例外后ENTRY/EXIT/EXEC/创建均失败，无native sequence回退。
10. 有/无SIGSYS delivery stop的真实形状合成路径均能终态结算；拒绝用户来源、
    未知code、错误arch/syscall或截断union，不伪造signal。
11. 错信号、正常退出、ECHILD、主动清理、错误birth终态均不得形成成功证据。
12. shared stop耗尽和既有失败锁存优先于例外；证据输出仍共享预留writer。

新增纯合成suite最多30秒；Linux普通JUnit bridge要求全部组实际通过、零skip，
Windows结果明确区分编译/平台skip。不新增真实探针或普通CI内原生能力测试。
实现的最终完整diff独立Review、适用CI和正常合并后，才将该组件列为已交付。

实际人工接线仍使用原完整清单中的两条probe，不重复运行、不另加次数；每probe≤5秒、
原组件50秒/人工全套900秒及阶段限制、512尝试/128live/2,000,000stop、证据10MiB与
writer分区均保持。全套固定fixture/task预留表、可信host/writer、复杂整树清理、最终
整体Review/CI、compatibility/inputhash与唯一登记全部完成前，不执行任何真实探针。
失败按原CAPABILITY_UNAVAILABLE/IMPLEMENTATION_INVALID/INCOMPLETE分类停止，不加预算、
不换ABI/seed/环境重跑。#140 BOUNDED_MISS、GoalWindow、#95/#96及runtime/default门保持。

本设计已通过 #219 接受；不表示内核能力或完整人工验收完成。

## 本次新增修改：原ENTRY/delivery resume归属接线

`PostHandshakeDispatcher.accept`在原策略完成后调用同一
`OwnedExecObservations.resume`，仅当其自己持有的模型已消费精确ENTRY时接管恢复。
该调用复核原owner、active wait receipt及其原始不可变facts、当前birth/group/TID/EXEC
代次、同一wire和共享失败/期限门。ENTRY只允许signal=0，delivery只允许原信号；
SIGSYS仍必须匹配SYS_SECCOMP union。未选birth、普通native和只观察配置保留原路径。

ENTRY在调用wire之前保留内部attempt；只在wire正常返回后标记完成，随后再次检查共享门。
失败不重试、不退槽、不恢复预算。后置检查失败仍保留返回事实，但撤销session。
每个冻结birth最多保留一个ENTRY及最后一次delivery attempt，空间不随stop数增长。
这些内部记录不允许调用方提交副本替代；没有新增证据writer或输出。

真实reap结算后，`observe_reap`新增核验同identity/manifest的原ENTRY resume已返回，
并核对保留receipt/facts没有漂移，再调用原模型终态匹配。仅向私有模型注入ENTRY已
不能完成该路径；原终态suite已使用假wait/dispatcher/resume完整接线。失败仍保留真实
reap和live释放，不能把失败回滚成活任务。

新增12个fake-only测试与Linux 30秒/零skip bridge覆盖原receipt、两种ABI和线程EXEC、
不确定wire、后置超时、外来wire/复制facts、信号注入或抑制、重复恢复、receipt漂移和
共享失败。所有调用均为假transport，未运行native/network/历史输入。

这只是原样resume的内部归属链，**不是完整no-injection证明**：可信宿主冻结的
source/interpreter/filter安装链、对全部写register/其他signal来源的约束与完整checker、
同根互斥writer、复杂cleanup及冻结fixture仍缺。模型终态不升级为
`KERNEL_TERMINAL_OBSERVED`，原执行门与预算不变，生产API/默认玩法无变化。

## 本次新增修改：纯匹配/消费子集

`scripts/fixed_witness_replay_abi_model.py`提供冻结`ProbeRecord`、`ExecIdentity`和
`AbiProbeModel`。恰好两条同源码/解释器清单以固定排序计算SHA-256，精确匹配canonical
路径字符串、完整argv与字节hash；路径检查仅为词法检查，不执行文件或proc认证。
birth/group_birth与exec代次采用正64位值，TID采用正signed-32-bit值；外层适配器将来
须从真实账本/exec事件映射，不能以模型生成task或exec身份。子birth不继承槽，同birth
或同TID不能占另一槽。匹配完整ENTRY后先变成`AWAITING_SIGSYS`，返回None；没有resume
token、ptrace/文件/账本/信号操作。BOUND再次EXEC只能撤销槽，不能重新绑定；错误锁存
全模型。外层失败可调用`fail`保持已消费状态，但这不是外层owner/deadline/stop门的接线。

本次有限子集覆盖上方第1–6组的匹配/消费、组8的失败后不可重试模型、组9的禁止再次
消费或EXEC及组12的既有失败锁存；新增18项纯合成测试（含有限表驱动反例），Linux
普通JUnit bridge强制30秒内完成且零skip。类型/整数宽度、完整六参数ENTRY和共享清单
漂移均拒绝；truncated wire仍经原decoder拒绝，不补零。原native guard保持不变。

未覆盖的真实能力：组7普通创建仲裁与槽的组合、组8真实owner/deadline/resume失败、
组11真实wait/reap和终态成功、组12真实共享stop/writer接线。模型没有终态成功接口，
也不能证明传入hash/路径/身份是真实观察。组10的union与delivery纯匹配见后续增量；
有界exec/proc观察也不替代真实EXEC认证与dispatcher/checker接线；不得因为子集通过
启用ABI例外或执行probe。所有原完整验收与停止门保持。

## 本次新增修改：SIGSYS只读wire字段

`decode_signal_info`要求完整128字节、精确bytes和匹配的wait信号。固定Linux
little-endian x86-64 tracer布局在offset 16读取`<QiI`：64位call address、signed
32位syscall和unsigned 32位audit arch。布局取自Linux v6.17
[`include/uapi/asm-generic/siginfo.h`](https://github.com/torvalds/linux/blob/v6.17/include/uapi/asm-generic/siginfo.h#L108)
的`_sigsys`及header/union定义；
[`arch/x86/include/uapi/asm/siginfo.h`](https://github.com/torvalds/linux/blob/v6.17/arch/x86/include/uapi/asm/siginfo.h)
不覆盖native x86-64布局。这里是native tracer的GETSIGINFO输出，不按tracee的
compat/x32入口arch切换为32位布局。静态源码依据不表示目标宿主能力已验证。
[`kernel/ptrace.c:1168`](https://github.com/torvalds/linux/blob/v6.17/kernel/ptrace.c#L1168)
的native GETSIGINFO调用`copy_siginfo_to_user`，compat tracer另走`copy_siginfo_to_user32`；
[`kernel/signal.c:3500`](https://github.com/torvalds/linux/blob/v6.17/kernel/signal.c#L3500)
复制native结构并清零扩展区，失败返回EFAULT。

仅`SIGSYS(31)/SYS_SECCOMP(1)`暴露`call_address/syscall/arch`；SI_USER/SI_TKILL
仅暴露sender，其他code仅保留header。错误arch/number和非零errno原样保留，decoder
不负责与冻结ENTRY比较，也不授予准入、resume或终态成功。`PtraceAccess.signal_info`
只发一次GETSIGINFO并委托decoder；错误不重试。内核成功返回时写完整siginfo，ptrace
没有返回字节数，因此截断检查针对传入decoder的wire bytes，不声称能检测假transport
返回成功却只写部分buffer的违约行为。

新增12项有限fake-only测试覆盖两固定ABI、union判别、signed/unsigned宽度、全部短
buffer、错误类型/信号、不可变性及传输失败；Linux JUnit bridge强制30秒内零skip。
既有普通signal dispatcher继续保留原SIGSYS；本轮未连接ABI模型、身份匹配或终态。
下一步的delivery纯模型匹配见下节，真实exec/proc认证、dispatcher/checker及真实wait/reap
仍待集成。完整fixture/host/writer/清理及全部原门
仍未满足，没有执行真实probe、network clean-build或历史22步。

## 本次新增修改：SIGSYS delivery纯模型匹配

`AbiProbeModel.observe_sigsys`仅在`AWAITING_SIGSYS`核对完整已消费身份、共享清单hash，
以及精确`SignalInfo`的`SIGSYS/SYS_SECCOMP`判别和固定ENTRY的arch/syscall。整数宽度与
类型必须正确（不接受bool或自定义相等对象）；call address要求unsigned-64，syscall要求
非负signed-32，arch要求unsigned-32，header errno保留signed-32，不额外猜测值。
sender union必须为空，拒绝SI_USER/SI_TKILL、未知code、缺失union、错误arch/number、
复用TID/不同birth/group/exec和错误阶段。任一错配锁存全模型，后续正确观察不能恢复。

方法返回None；匹配和重复匹配均保持已消费状态，不产生resume token、终态成功或额度
释放。不要求存在delivery stop，也不比较call address与ENTRY instruction pointer，后者
不是本设计的身份依据。普通信号策略仍由外层负责，本方法不接受普通信号充当SIGSYS事实。
模型没有调用ptrace、wait、reap、文件认证或dispatch；native guard不变。

新增12项有限fake-only行为测试（模型共30项），覆盖两模式的真实wire解码到模型匹配、
身份/清单/阶段/判别/union反例、失败优先与跨槽锁存、无delivery状态及截断wire边界。
Linux普通JUnit bridge仍强制30秒内全部通过且零skip。本轮只补上组10的delivery事实
匹配子集；真实wait/reap终态、来源与无注入链、exec/proc认证、共享预算及owner门仍未接线。
后续有界观察子集见下节；真实EXEC身份认证、dispatcher/checker与终态仍待组合，
所有原人工验收门保持。

## 本次新增修改：有界exec/proc观察子集

`scripts/fixed_witness_replay_abi_observation.py`新增`AbiExecObserver`、只读`ProcFiles`
与不可变`ExecObservation`。预期`ProbeRecord`仍来自独立冻结清单；输入`ExecIdentity`
必须来自唯一owner已认证并完成迁移的EXEC停点。调用方必须提供`check()`，在每个I/O
及hash分块前后复核原deadline、共享账本/失败门和当前仍停止的exec代次，并返回该
身份；本组件没有提供此可信生命周期适配，不能以任意callback或proc starttime替代。

观察两次比较`stat`出生时间与稳定字段、`status`的Pid/Tgid/TracerPid/tracing-stop、
精确NUL argv、源码/解释器canonical路径和完整字节SHA-256。已迁移EXEC的Pid须等于
Tgid；真实`/proc/<tid>/exe`链接须精确匹配冻结解释器，打开magic link后对实际映像
hash，并比较其dev/inode/mode/size/mtime/ctime与冻结解释器文件。源码和解释器最后
路径分量用`O_NOFOLLOW`，全部只读open用`O_CLOEXEC|O_NONBLOCK`，fstat拒绝非普通文件。
hash前后metadata不一致、两轮证据不一致、消失、权限错误、deleted exe和路径漂移均
失败。没有读取environ、启动辅助进程/线程或进行任何ptrace/wait/resume。

这些是保守I/O上限，不是新增研究预算：每个proc文件实际读取最多64KiB（不依赖其
通常为0的st_size），源码最多1MiB，解释器与proc exe各最多64MiB；每次读取最多64KiB，
多读1字节只用于拒绝超限。只在内存保留有限proc bytes或流式hash，不输出证据文件；
未来保留记录仍须经原共享10MiB writer。原deadline不重置；检查能发现I/O后超时，
但无法强制抢占阻塞的宿主文件系统操作，仍依赖原可信host前提。超限或失败停止，
不自动提高上限或重试。fd在post-open检查失败和异常时也只close一次，close失败传播。

返回观察值不绑定`AbiProbeModel`、不生成exec代次、不授予resume/ABI/终态权限；一次
尝试后不可重试，错误锁存本组件，调用方还必须锁存原session。仍不证明Python执行了
哪些内存指令，也不抵抗恶意host/tracee并发改写后恢复；冻结文件与独占停点必须由
原可信host/owner保障。真实EXEC来源/代次/共享门适配、dispatcher/checker、真实wait/reap
及整体验收尚未实现，native guard保持拒绝，未执行任何真实probe或历史输入。

新增20项有限fake-I/O测试覆盖两模式、身份/代次与owner漂移、argv、proc状态与出生
变化、路径/字节/映像inode错配、实际读取和fstat上限、分块检查、metadata漂移、
post-open超时、I/O/close失败及不可重试。Linux普通JUnit bridge要求30秒内20项全部
通过且零skip；只mock native open flags，不打开真实proc、不执行fork/ptrace/探针。
Windows只提供这些假I/O行为及Java编译/平台skip证据。README/AGENTS架构无需变更。

## 本次新增修改：SIGSYS终态事实纯模型

`AbiProbeModel.observe_terminal`只在已消费的`AWAITING_SIGSYS`阶段，精确核对mode、
birth/group/TID/exec、冻结manifest和完整`WaitStatus(signaled, SIGSYS)`；拒绝正常退出、
其他终止信号、stop/event、额外exit/event字段、bool信号和非bool core-dump标志。
core-dump位可真可假，不作为来源证据；不要求先出现delivery stop。

匹配一次返回None并进入`TERMINAL_FACT_MATCHED`，不能再次匹配、重绑、消费ENTRY、
观察delivery或EXEC。错配锁存整个模型，原失败优先，另一个独立槽仍可正常匹配。
这个纯状态刻意不同于设计第5步的`KERNEL_TERMINAL_OBSERVED`：传入的dataclass和
decode后的bits不证明真实wait/reap，不调用ledger、不释放live、不产生成功或resume凭据。
未来唯一wait owner必须先核对真实birth并完成真实reap结算，再提交此事实；结算失败、
ECHILD、主动cleanup或原owner/deadline/shared-stop失败必须先锁存模型/session。
模型无法从终态bits判断主动注入或过滤器来源，这些仍是可信适配器和最终checker的职责。

新增12项有限fake-only行为测试（模型共42项），覆盖两模式、有/无delivery与core位、
正常退出/错信号/stop、身份/清单/阶段/类型错配、一次性消费、跨槽与失败优先。
Linux普通JUnit bridge强制30秒内42项全部通过且零skip。测试中的ECHILD/cleanup仅
验证调用方锁存后的优先性，不声称已实现真实wait错误或清理接线。本轮没有新增系统
调用、native probe或历史输入执行；真实wait/reap、身份认证、无注入/来源checker、
共享门与全部整体验收仍未完成，native guard与原预算/停止条件保持。

## 本次新增修改：唯一wait owner的EXEC观察门

`OwnedExecObservations`接受至多两个执行前冻结的birth/ProbeRecord映射，只能由一个
`PostHandshakeDispatcher`领取。普通EXEC路径先匹配native entry、读取一次GETEVENTMSG、
完成ledger迁移，再递增该birth的成功EXEC代次；不会以proc starttime或外部整数冒充代次。
选中birth仅允许一次观察，第二次EXEC失败且保留已迁移身份，不刷新槽或重试。

观察在正常resume之前同步完成。每个I/O/chunk沿用原launch owner/deadline，核对reader、
creation dispatcher/lifecycle/policy、ledger和wait owner的失败锁存，要求当前active wait
就是sole owner刚保存的正TID EXEC结果，GETEVENTMSG已成功且old/new TID匹配；核对当前
birth/group/TID、代次、独占wire和待成功exec-exit状态。原始accept调用没有该实际wait
记录时不得观察。非leader EXEC使用迁移后executor birth/wire，旧leader仍只按既有迁移
规则退休。观察失败保留cleanup身份及全部计费、不resume；普通resume失败仍锁存外层，
已保存的观察不是可用能力票据。未选中birth不读文件。

新增15项fake-only组合测试及Linux普通JUnit桥，覆盖迁移、一次性选择、共享失败、
截止时间、owner/epoch/wire/receipt漂移、proc错配、I/O失败和不确定resume。
这些测试不执行真实wait/ptrace/proc/probe。该配置目前没有接入network入口；尚不认证
冻结映射的host来源、filter/no-injection、证据写入总门或SIGSYS真实终态，不绑定
AbiProbeModel、不放开compat/x32，完整supervisor/host/fixture和原整体验收仍待完成。
原合作式可信host及不可抢占文件I/O的限制保持；不授予历史22步或原生能力执行许可。

## 本次新增修改：EXEC观察到两槽模型的绑定

`OwnedExecObservations(bind_abi=True)`从执行前冻结的两条birth/record映射新建自己的
`AbiProbeModel`，要求完整x32/compat清单共用源码与解释器身份；不接收外部已使用的模型。
默认观察配置不构造模型。真实观察完成后，仍在原sole-owner EXEC停点中，先核对原门，
再将观察得到的record和birth/group/TID/exec代次绑定，随后再次核对门，最后保存观察。
失败同时锁存模型与外层session；绑定后门失败保留已绑定槽和真实迁移身份，不回滚、
不重试、不resume。诊断`abi_state`仅返回状态字符串，不提供可转交的准入票据。

新增13项fake-only组合测试及30秒Linux普通JUnit桥，覆盖两种模式、nonleader迁移、
冻结清单不一致、raw accept拒绝、argv错配、模型既有/I/O中失败、绑定后deadline失败、
重复EXEC与普通native拒绝边界。没有接入ENTRY例外、delivery/terminal或checker；
后续普通dispatch/resume失败仍由原外层session锁存，模型BOUND状态不能单独授权动作。
host来源、共享证据writer、完整supervisor/fixture与原整体验收前置保持。没有运行
probe、network clean-build或历史22步，不改变生产架构，README/AGENTS无需同步。

## 本次新增修改：唯一wait owner的结算后终态记录

`BoundedWaitOwner`现在在正TID定向wait匹配原birth、普通dispatcher完成真实reap结算之后，
保存不可变`SettledReap(before, after, raw, status)`。原owner/deadline、reader/ledger、
creation dispatcher/lifecycle/policy和独占wait注册仍须有效；核对相同birth/group/TID、
`reaped`标志、无未结算终态及绑定已移除，才记录一次。最多保留511个birth记录，来自
原512尝试上限（含tracer），不是新增task/stop预算；只保存在内存，不输出证据文件。

`settled_reaps`仅返回不可变诊断快照；`require_settled_reap`要求原owner内部保存的同一
对象及当前相同账本终态，并重验共享门，不接受相等副本、raw tuple、另一个session或
直接`accept`/cleanup路径。验证失败锁存session，不得换正确记录重试；已有诊断保留，
后续失败撤销可用性。此方法只核验事实来源、不消费槽、不resume、不再wait或释放额度。

ECHILD不算reap；结算前失败保留已消费wait及原未结算集合。结算后门失败保留真实额度
释放与last_wait，不回滚真实reap、不补发成功记录。非leader EXEC后只记录迁移executor
birth的真实reap，旧leader的`exec-replaced`不是终态wait。退出码、各种终止信号和core位
均原样保存；不把SIGSYS的bits升级为过滤器或无注入证明。

新增15项fake-only组合测试及Linux普通JUnit桥，强制30秒内15项全部通过且零skip；覆盖
复制/跨owner伪造、后续失败撤销、消费后超时、结算失败/后置门失败、终态身份漂移及
thread EXEC迁移。只注入fake wait/ptrace，不执行native probe、proc、network clean-build
或历史输入。本组件尚未关联认证EXEC代次、已消费ABI ENTRY与manifest，未调用
`AbiProbeModel.observe_terminal`、未产生`KERNEL_TERMINAL_OBSERVED`或完整能力凭据。
ABI/checker/source/filter/no-injection及共享writer总门和整体验收仍待接线；native guard、
原预算与停止条件保持，README/AGENTS生产架构无需变更。

## 本次新增修改：真实reap的EXEC代次快照

#230的`SettledReap`新增可空`exec_identity`。唯一`BoundedWaitOwner`在调用真实
wait之前，从普通dispatcher已成功安装的EXEC代次与当前birth/group/TID建立不可变
快照；只有真实终态成功结算、原owner/deadline/共享失败门仍成立、代次未漂移时，
才随原始wait bits保留。没有EXEC的终态保持None，不从SIGSYS、proc或外部整数推断。
非leader EXEC使用迁移后的executor birth及原group；退休leader没有reap记录。

`require_exec_reap`先验证原owner中的同一记录对象，再精确匹配有效`ExecIdentity`
及仍保留的installed generation。复制记录、错birth/group/TID/代次、失效owner、
后续失败和deadline均锁存session；不刷新快照或重试。若wait/结算期间发生代次漂移，
保留实际reap及已经释放的live credit，不签发记录、不回滚实际回收。

此门只证明执行代次与真实reap的关联，不认证probe绑定、manifest、已消费ENTRY、
SIGSYS原因、filter或no-injection；正常退出和其他信号同样可保留代次。尚未接通ABI
terminal adapter/checker或network入口，native guard和原预算/停止条件保持。
新增14项有限fake-only测试及Linux普通JUnit桥，要求30秒内全部通过、零skip；无真实
wait/ptrace/proc/信号/探针或历史输入执行。README/AGENTS架构无需修改。

## 本次新增修改：结算后EXEC reap到原绑定模型的终态接线

`BoundedWaitOwner._retain_reap`完成原真实回收、保存不可变记录之后，调用其原
`OwnedExecObservations.observe_reap`。只有显式`bind_abi=True`配置启用模型终态核验；
默认仅观察配置保持原行为。先通过原owner的`require_settled_reap`核对同一记录对象、
账本与共享门，再从冻结birth映射和本配置已完成的EXEC观察取得record/identity，
经`require_exec_reap`复核真实回收的installed generation。终态输入不接收外部mode、
manifest、identity或模型；清单使用本配置自建模型的冻结manifest。

原模型必须已消费同一绑定的ENTRY，再精确匹配SIGSYS终态。成功仍只进入
`TERMINAL_FACT_MATCHED`，不是`KERNEL_TERMINAL_OBSERVED`或filter/no-injection证明。
之后再次核对原owner/deadline/共享门与EXEC代次。错配、重复调用或后置门失败锁存
模型及session，实际reap、已释放的live credit和诊断记录均保留，不回滚、不重试，
不新增wait、resume、task/stop预算或证据文件。非选中birth不消费模型槽；非leader
EXEC核对迁移executor birth及原group，退休leader不能充当终态记录。

新增16项有限fake-only组合测试和30秒Linux普通JUnit桥，要求全部通过且零skip。
测试明确直接向私有模型注入合成ENTRY，再由fake定向wait走普通终态回收路径；
这不是dispatcher的ABI准入或真实probe验证。覆盖两模式/core位、thread EXEC、
无ENTRY/正常退出/错信号、身份错配、原记录/副本/跨owner重放、缺少完成观察、
既有模型失败、raw accept/ECHILD不提升、共享门/后置deadline/代次漂移及默认边界。
原native guard继续拒绝非native ENTRY；ENTRY/delivery准入、source/filter/no-injection
checker、host证据writer及整套fixture仍待接线，不能执行network clean-build或历史
22步。原预算与所有执行停止门保持；README/AGENTS生产架构无需修改。

## 本次新增修改：唯一wait停点的ENTRY／delivery完整模拟接线（2026-11-11）

接续 #235 的真实reap到模型终态连接，`PostHandshakeDispatcher.accept`将本次
`StopReader`读出的原始不可变facts保留在唯一owner的当前receipt中；
`OwnedExecObservations.observe_stop`只消费该对象，不接受副本、raw accept、其他owner、
脱离active wait的旧receipt或调用方传入的身份/manifest。核对当前ledger birth/group/TID、
EXEC generation、原冻结记录与完整观察、原owner/deadline及共享门，并在消费后复核。

BOUND内native入口/出口仍走原完整sequence/创建仲裁。只有固定x32 socket或compat
socketcall精确ENTRY、且之前native序列/EXEC exit已完成，才先消费为AWAITING_SIGSYS，
再回到同一dispatcher执行一次`resume_syscall(tid, 0)`。不写寄存器、不截断ABI、不改
返回值、不注入信号、不复制创建仲裁；`CreationSyscallDispatcher`的native guard未改。
AWAITING_SIGSYS后的任何syscall/创建/EXEC停点在政策变更前失败。可选SIGSYS delivery
必须由同一停点读取的SYS_SECCOMP union精确匹配，再保留原signal=31恢复；其他普通
信号保持原政策，不算成功。终态仍经 #235 的原owner实际reap结算后匹配。
后置门、普通政策或resume失败会撤销整个模型/session；已消费槽不恢复，不能重试。

新增16项有限fake-only测试及30秒Linux普通JUnit桥；两种模式、两种EXEC身份与有/无
delivery都走相同EXEC→native exit→ENTRY→可选delivery→reap路径，不再注入私有模型
ENTRY。覆盖未知ABI/模式、未完native序列、raw/copy/旧receipt、代次漂移、缺少完成
观察、消费后期限/共享门失败、resume不确定、重复停点和伪造SIGSYS。原绑定回归改为
继续拒绝相邻非授权x32号；预先篡改模型绑定在完成观察门提前失败。

这是内部适配接线的模拟验证，不是原生能力、过滤器安装/来源链、无注入记录或整套
人工验收。没有增加CLI/runner入口，也没有调用原KernelTest或执行探针。下一步仍须
接通冻结source/filter/no-injection checker、host互斥writer/清理和整套fixture，最终整体
Review/CI、兼容性/inputhash与唯一登记后才可执行。#170A/#174已明确接受；
原预算、#140/GoalWindow、#95/#96及runtime/default停止门不变。

## 本次新增修改：网络 launcher 与 probe 的相邻 EXEC 接线

`ProbeLaunch`保存可信host执行前冻结的原`network.chain`记录的canonical JSON及
对应`ProbeRecord`，严格关联解释器hash、完整probe argv、policy hash和库身份；
不运行chain/validate/install，不接受调用方布尔证书，不产生能力或执行权限。
宿主仍须在原来源认证链冻结该记录，不能把随意构造的JSON当成来源认证。

`OwnedExecObservations(network_launches=...)`要求全部所选birth的精确映射。
第一次原owned EXEC观察launcher完整argv（包括原JSON）、源码/解释器/库字节与
继承filter基数，不绑定ABI模型；原wait owner在原wire上仅执行一次signal-zero
resume，尝试在调用前保留，正常返回与共享门通过才能进入下一阶段。
第二次EXEC必须是同birth/group/TID的相邻代次且proc start time相同，核对probe
源码/解释器/库、精确argv和NoNewPrivs=1/Seccomp=2/filter基数加一后才绑定原模型。
随后复用现有精确ENTRY、原样delivery、真实reap和终态匹配路径。第三次EXEC撤销，
birth不继承；不创建任务、wait owner、预算或额外的resume。

两阶段观察均复用`AbiExecObserver`的双快照、有界chunk、每次I/O前后原共享门及
描述符一次关闭。原observation-only与不配置network_launches的路径保持原契约。
14项fake-only测试覆盖两种ABI、线程EXEC迁移、可选delivery到终态、跳过launcher、
源码/库/安全状态漂移、非相邻代次/生命周期变化、不确定resume及超时保留、再次EXEC
撤销和冻结配置错配；Linux JUnit bridge要求30秒内14项零skip。

这只是可信cooperative host前提下的安装来源观察链，不是filter字节/运行中完整性
证明。完整no-injection checker、host冻结来源与互斥writer、复杂清理、全套fixture及
原最终整体Review/CI/执行门禁仍缺，不能标记KERNEL_TERMINAL_OBSERVED。
未执行原生probe、network clean-build或历史22步，未改冻结guard/预算/生产代码。

## 本次新增修改：原network probe wire干预约束

配置`network_launches`并绑定ABI模型时，`OwnedExecObservations.observe`在第一次
launcher EXEC的有界文件观察前封闭该birth的原`PtraceAccess`；第二次EXEC只接受
同一对象的相邻identity/原manifest。原wire失败或已有未分类写入不能重新绑定。
纯观察与既有非network配置不获得这条检查，也不构成完整验收路径。

`PtraceAccess._request`在原transport调用前登记attempt，正常返回才登记completed；
不确定、禁止或未知请求永久锁存失败。封闭后仅允许现有只读请求、一次性原wait
resume和已校验native clone3的精确寄存器写入；没有CONT/DETACH/POKE/SETSIGINFO、
任意SETREGSET或raw SYSCALL旁路。clone3仍先由原创建dispatcher扣尝试预算，再按
既有寄存器校验只写orig_rax=-1和固定ENOSYS；单次scope核对完整register bytes，
不把clone3规则扩展成ABI结果改写许可。无新增任务、预算或证据writer。

每次network resume由原configuration同步持有一次性scope；原wire回调消费它，复核
当前owner/active wait receipt/facts/birth/EXEC/manifest和原signal。仅持有configuration、
identity和manifest不能再次resume。普通launcher/probe native stop复用原创建策略后
也走同一入口；ABI ENTRY和delivery继续复用既有专门校验。

`BoundedWaitOwner`在实际wait前只读保留原wire，正常真实reap仍先结算和保留；
`observe_reap`再核对该对象与原network wire相同且wire无失败/未完成请求。事后发现
注入尝试或换wire会撤销成功，但不回滚真实reap/live credit。

新增15项fake-only测试与30秒Linux零skip bridge；既有14项两阶段EXEC fixture改为
经过实际typed ptrace编解码的假kernel。覆盖旁路请求、外来/重复resume、原owner token
复用、source观察中干预、不确定调用、clone3边界及terminal前换wire。

这是可信cooperative host内原wire的约束与记账，不证明Python私有状态防篡改、其他
独立transport/外部signal不存在、实际filter安装字节或完整no-injection能力。host原始
来源冻结/互斥同根writer、完整filter checker、复杂cleanup与整套fixture及原整体最终
Review/CI/compatibility/inputhash/唯一登记仍必需。整体原生验收、研究结果与用户可见
功能仍未完成；未执行任何native probe/network clean-build/历史输入，停止门不变。
