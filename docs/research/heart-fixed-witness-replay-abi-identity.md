# 固定 ABI 反例的出生身份与一次性准入设计

本设计细化已明确接受的 [#174 第6条](heart-fixed-witness-replay-task-budget-proposal.md)，
不增加 ABI 例外、探针、原生执行次数或预算。纯匹配/消费、只读union解析、delivery/终态事实匹配与
有界exec/proc观察与唯一wait owner的EXEC代次/停点门子集已实现，SIGSYS终态接线尚未实现；
当前 dispatcher 继续拒绝所有 compat/x32 syscall。
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

新增14项fake-only组合测试及Linux普通JUnit桥，覆盖迁移、一次性选择、共享失败、
截止时间、owner/epoch/wire/receipt漂移、proc错配、I/O失败和不确定resume。
这些测试不执行真实wait/ptrace/proc/probe。该配置目前没有接入network入口；尚不认证
冻结映射的host来源、filter/no-injection、证据写入总门或SIGSYS真实终态，不绑定
AbiProbeModel、不放开compat/x32，完整supervisor/host/fixture和原整体验收仍待完成。
原合作式可信host及不可抢占文件I/O的限制保持；不授予历史22步或原生能力执行许可。
