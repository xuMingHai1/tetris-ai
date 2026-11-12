# 固定动作回放：写入前累计配额组件

## 本次新增修改：宿主启动、剩余时钟与直接 tracer 终态接线

`FixedWitnessWriterHost.createSession -> launchAndObserve(ProcessBuilder)` 现在从创建
独占目录前开始同一900秒时钟，在启动前永久消费唯一 tracer attempt/live 预留。
启动和原 grant 写入在可信 host 的 virtual thread 上执行；原调用线程同时保留更早的
截止，启动延迟/阻塞写入不能重新计时。host 在总截止前保留原3秒清理时间，失败只使用
原剩余时间 kill/有界 wait 直接 child；迟到返回的 Process 也先保留再杀死，尽可能在
原总时限内 wait。未知/未返回的启动与未观察到的 terminal 均不能声称已清理。
host 不用 shell/观察辅助进程，tracer stdout/stderr 丢弃，固定后缀按原32KiB写前计费。

监督路径使用 `FIXED_WRITERS_V2`，保持原三个互斥 writer 数值，并传递发送时剩余
纳秒和已扣1 attempt/1 live。`HostWriterHandoff.receive` 只缩短原 caller deadline；
Java 仍保留包含传输延迟的更早截止。`prepare` 要求实际 `TaskLedger` 恰好只有原
tracer 槽且没有 stop/pending/失败，不能复制槽或先启动 coordinator 后再领取。
既有 V1 独立交接兼容性保留，不提供受监督启动证明。grant EOF/独占 claim、原
prepare receipt/source recheck、stdout/evidence writer、实际 supervisor/cleanup/reap继续复用。

Java fake Process 回归覆盖计时含准备/启动延迟、启动失败不重试、grant失败、active
超时的原3秒收尾、迟到 child、原异常与cleanup异常、非零退出和独占session；Python
新增剩余时间/原账本反例。普通 Linux Java→Python 真管道测试现在调用实际启动入口，
内核 fork/wait/ptrace/pidfd/signal及来源读取仍注入，生命周期仅为 DRAINED_OBSERVATION。
host 后缀明确 `DIRECT_TRACER_EXIT_0 / SUITE_UNVERIFIED`，原 INCOMPLETE 前缀保留。

有限剩余清单：完整受认证 Java host command/source/toolchain/manifest冻结与固定
task/fixture调度；coordinator ALL artifact/JVM suballowance实际传输；完整filter/external
no-injection checker；尚缺的复杂cleanup/jobcontrol；全fixture和EXITKILL负例；整体最终
Review/CI/compatibility/inputhash及唯一登记。文件系统和平台start/destroy原语本身不能由
Java强制抢占，迟到启动只能在返回Process后清理，沿用cooperative host限制；本组件不
证明整树终态或完整硬时限能力。整体原生验收、研究结果、用户可见功能均尚未完成。
没有原生probe、network clean-build或历史输入执行；生产架构/README/AGENTS、原guard、
冻结协议/预算及执行停止门保持。

## 本次新增修改：已接受的人工套件writer分区

用户2026-11-09在会话 `01a1bdd5-5d02-7580-95b4-42302a6fa60a` 明确接受
#174具体修订，包含[任务预算方案](heart-fixed-witness-replay-task-budget-proposal.md)
的三个独立writer额度。`scripts/fixed_witness_replay_writers.py` 的
`reserve_writers(root)` 将全新10MiB根额度一次原子预扣，再永久分为tracer32KiB、
宿主32KiB、协调器10MiB-64KiB。已使用、较小或重复分配的root拒绝，不能在失败时
部分预留报告额度；并发分配仅一个成功。根charge包含全部预留，不再累加子charge。
失败前缀必须从writer自己的分区预留，未使用余额不回收、不跨区借用。

这是**宿主侧额度分配组件**，尚未接入人工监督器。不能fork/pickle这些ByteQuota，
或在不同进程各重建一个同额度对象来声称共享计费；后续仍须将每份永久预留唯一绑定到
实际执行该限制的writer，认证所有权和输出路径，并覆盖复制品及错误前缀。
本组件不启动task、不执行ptrace/网络/clean-build能力探针、历史22步或完整验收。
7项synthetic测试覆盖完整分配、非法root、重复/并发分配、额度隔离、失败预留和
失败I/O不退款；普通Linux Wrapper桥接执行这7项，不代替完整能力验收。
#170A存储分离已由 #191 交付；构建缓存/target不受该证据分区磁盘硬界限制。
其余整体最终Review/CI、唯一验收登记和历史执行停止门保持。仅test-only设施，
不改变生产架构，README/AGENTS无需调整。

## 本次新增修改：协调器 stdout/stderr 接入原监督器

`TracerSupervisor.for_host()`现在在唯一fork前建立`CoordinatorOutput`的两条pipe，
将write FD原样交给`CoordinatorLaunch.for_host()`，从已永久分区的
`writers.coordinator`消费stdout/stderr保留前缀。该partition只准一次stdio claim；
原有artifact/JVM子预留仍从同一个对象扣除，不额外建立root或按日志大小重计。
构造失败不退claim/额度、不覆盖旧文件；额度耗尽在fork前拒绝。

`run -> launch/finally关闭父write端 -> InitialWaitOwner/BoundedWaitOwner`在每次
实际wait前通过同一个`_wait`排空两条pipe，每流最多16KiB，先charge后write/flush。
输出失败发生在消费wait之前，不会丢失EXEC/terminal receipt；溢出只保留可计费前缀，
锁存后走原`SettledProcessCleanup`的pin/kill/实际reap。cleanup仍有界读取并丢弃后续
字节，不重试失败sink、不跨writer借额度。全部birth真实reap后仍须在原deadline内
排空EOF，再关闭FD/sink；sink采用无缓冲二进制文件，避免失败后close隐式重写；EOF本身不结算task，close失败不能返回drained成功。
未配置output的既有直接构造路径保持原调用方负责stdio的契约。

21项集成测试使用真实临时pipe/文件，但全部fork/wait/ptrace/pidfd/signal均为fake。
覆盖原双EXEC/精确ENTRY/typed wire/reap链、两个输出流、artifact争用、超额后清理、
不丢wait、末尾排空、ECHILD、期限、I/O/close失败及一次性factory；Linux普通JUnit
桥强制21项/零skip/30秒。它们不是原生能力或完整network clean-build验收。

有限剩余清单：可信Java宿主的启动/终态观察与三个writer的跨进程互斥交接；协调器
全部artifact/JVM保留额度与原来源/manifest冻结、完整filter/external no-injection checker；
剩余复杂cleanup和完整冻结fixture/task预留表、EXITKILL终末负例；最终整体Review/CI、
compatibility/inputhash、唯一登记和原执行前门。此接线没有完成上述项，不可把
进程内原ByteQuota的共享升级为Java/Python跨进程传输证明，不fork/pickle该对象。
没有native probe、network clean-build或历史22步执行；研究结果和用户可见功能无新增。
仅test-only设施，生产架构/README/AGENTS、预算/guard及全部停止门不变。

## 本次新增修改：原 tracer 生命周期接通有界证据 writer

`TracerSupervisor.for_host()` 先唯一领取原 `writers.tracer` 的32KiB分区，
以 `write_exclusive` 预扣并独占写入 `tracer-incomplete.txt`，再准备协调器管道及
native wire。领取或前缀写入失败不重试、不创建task；构造中途失败保留前缀。
同一分区不可再次创建证据writer，也不向host/coordinator借用余额。

`run()` 的外层finally在原监督循环、既有kill/reap及协调器输出关闭全部返回后，
一次性写 `tracer-lifecycle.json`。固定字段只记录原ledger累计计数、launch/握手、
输出关闭和失败/cleanup状态，不转印异常、命令或trace。`DRAINED_OBSERVATION`仅是
原账本排空观察，`suite_verified`永远false；immutable INCOMPLETE前缀不删除、不覆盖。
此文件不是冻结人工套件receipt、完整report或外部checker证明。

完整记录在打开前计费；写入失败保留charge，禁止重试。若已有原执行异常，报告错误
单独留在 `evidence_error`，不遮蔽原错误与cleanup结果；若原循环已排空而证据失败，
禁止成功返回并撤销drained。记录生成在output close之后，因此close错误不会留下
虚假的排空记录。共享 `write_exclusive` 改用无缓冲FileIO，避免失败flush后的close
隐式重写；这是同一证据失败语义所需的额外修改，既有调用方的预扣/独占语义不变。

13项fake-only组合用例覆盖原实际监督/失败清理、deadline前失败、close错误、配额耗尽、
文件碰撞、失败前缀、一次性领取、真实FileIO失败不重试及双重失败不遮蔽。新增Linux
普通JUnit桥强制30秒内13项零skip；native fork/wait/ptrace/pidfd/signal均为注入。

有限剩余集成清单：
1. 本轮关闭原tracer生命周期到32KiB本地证据writer的实际接线缺口。
2. 可信Java宿主的启动/终态观察、三个writer跨进程互斥交接、协调器全部artifact/JVM
   enforcement、原source/manifest冻结及fullfilter/external no-injection checker仍缺。
3. 未消费EXEC/de_thread、leader提前退出、复杂jobcontrol/ptrace/pin失败整树清理，以及
   全部冻结fixture/task预留表和EXITKILL终末负例仍须完成。
4. 原整体最终revision独立Review/CI、compatibility/inputhash、唯一登记及执行前门保留。

整体里程碑及原生/network clean-build验收未完成；未执行历史22步或native probe。
研究结果、用户可见功能本轮无新增。该接线仅为test-only设施，未改变生产API、
冻结协议/guard/预算或正常运行方式；README/AGENTS无需同步。

## 本次新增修改：Java宿主writer到tracer实际工厂（2026-11-12）

`FixedWitnessWriterHost`在规范化session目录独占写入宿主INCOMPLETE前缀，
仅持有32KiB宿主writer；`handoff(Process)`通过既存直接子进程stdin一次性发送
固定10MiB分区grant并关闭管道。写入/flush失败不以close重试，也不重新发行；
宿主writer的非法名、额度不足或I/O失败永久锁存，禁止后续写入与handoff；
调用方必须在原硬deadline内终止并回收子进程。此类没有启动进程或执行诊断的入口。

`HostWriterHandoff.receive`在原deadline内，以唯一线程非阻塞读完有界grant并要求EOF，
核对当前parent/receiver PID和三个精确冻结额度，拒绝额外字节、超时和非管道。
接收端只建立32KiB tracer与余下coordinator额度，`writers.host=None`，不复制Java
ByteQuota或重建第二个宿主writer。独占claim文件先计入tracer额度；同目录重复交接失败。
这依赖可信原始宿主、直接继承管道和无外部注入的前提，不将grant文本当作身份凭证。

`prepare`一次性消费该grant，直接调用已有`TracerSupervisor.for_host`，保留原deadline、
来源receipt、输出管道、tracer证据与真实reap接线；即使prepare失败也不允许重领。
普通CI的Java/Python真实管道用例走这条调用链，fork/wait/ptrace/signal全部注入，
不运行原生tracer、能力探针、network clean-build或历史输入。另有13项Python回归覆盖
实际成功/失败清理、原异常、writer计费与重复/漂移/超时拒绝。
grant与复用fake launch共用同一时钟；低uptime回归避免将固定夹具时间与真实deadline混用。

有限剩余清单：本轮只接通writer发行/接收至实际supervisor工厂，不是完整可信宿主。
仍需原Java宿主的来源/toolchain/manifest冻结、启动前计时与tracer槽预留、
原硬deadline终态监督/取消，以及协调器全部artifact/JVM子预留的实际接线；
完整filter/external no-injection checker、冻结task schedule/fixtures、剩余复杂cleanup
与EXITKILL负例仍缺。整体最终revision Review/CI、compatibility/inputhash及唯一登记
继续是原生验收前置。本轮没有研究结果或用户可见功能，未变更生产代码、冻结guard或预算。
README/AGENTS的生产职责不变，无需同步。

## 既有组件与交接记录

本组件落实[已接受设计](../design/heart-fixed-witness-replay.md)的固定10MiB输出上限，
只提供合作式writer的共享计费原语及管道接线，不是完整诊断supervisor。
没有历史22步执行、collect入口、网络隔离或runtime许可。

## 计数与写入语义

`scripts/fixed_witness_replay_quota.py` 的 `ByteQuota` 从不超过10MiB的额度开始，
以锁保护所有分配；完整artifact在独占创建前一次性charge，管道只保留可分配前缀。
`write_exclusive` 接受精确二进制字节，超额不创建文件；创建、短写、flush/fsync失败均不返还额度。
故charge是已保留字节的保守上界，不能作为精确磁盘占用实测值。

`reserve(n)` 在父额度中永久扣除n，再交出最多n字节的子额度。子额度还可切分，
但不回收未使用余额；不能将子charge再次加到父charge。未来supervisor应先从同一根额度
切出失败记录余量，再分配普通管道和产物，失败记录没有额外的10MiB之外额度。
跨进程writer只有实际执行相同上限时才能受益；仅传一个数字或定时扫描目录不足以硬限制。

`OutputBudget(quota=shared)` 将stdout/stderr前缀写入计费给同一额度；未传quota时保持
原pipe-only行为。每个OutputBudget由一个监督线程使用，多个合作式writer可共享ByteQuota。
日志只在实际写入时记一次，不能再按相同日志的目录大小重复记账；新增保留副本则须另计。
发生I/O失败时`written`包含已扣除但可能未全部落盘的前缀，属于保守上界。

## 验证与后续门

11项小型人工Python测试覆盖并发争用、嵌套预留、失败记录余量、artifact超限不创建、
原文件保护、短写/后写失败不返还、pipe/artifact共用和耗尽后禁止启动。
Linux普通JUnit桥强制执行全部11项，含真实Python子进程超限终止；不使用历史CSV或Java诊断。
原进程/prepare/pipe测试继续验证兼容性。

本次新增修改：prepare的before/manifest/results复用管道的同一根额度，failure使用从该根
永久预留的4096字节；见[准备组件](heart-fixed-witness-replay-prepare.md)。
本次新增修改：JVM READY/verified在启动前永久预留同根额度，由实际Java writer
按精确字节写前执行，argv及认证绑定并检查合计；见[父管道组件](heart-fixed-witness-replay-pipe.md)。
明确未接线：Maven生成classpath、HOME/TMP外部writer、
实际journal/report写入尚未统一使用此额度。现有调用者仍可能自行创建新的根额度；
没有完整supervisor，不能宣称整体session上限已经落实。
下一项从这些真实writer入手，对受控写入复用root，对外部writer实施预留上限，保留失败收尾余量。
之后仍须网络限制、完整journal/supervisor、兼容性/inputhash更新、整体最终Review/CI和唯一登记，
才允许历史动作诊断。#140/GoalWindow停止、#95/#96、reserved/provider/runtime边界不变。

这是test-only离线设施，不改变生产包职责、构建输入或运行方式，README/AGENTS/ADR无需变化；
研究索引补充组件入口。不更改冻结设计预算、原guard或selector。


## 本次新增修改：构建/JVM进程日志失败不再缓冲重写

原 `run_process` 的stdout/stderr日志仍是BufferedWriter。`OutputBudget.consume`
已在写前扣费，但底层写入失败后，finally的flush和上下文close可能再次提交缓冲字节。
这与原失败不重试要求冲突；协调器管道writer已有无缓冲实现，不能覆盖这个既有调用方。
本次仅将这两个真实日志sink改为独占raw FileIO；仍先计费，短写和失败不退款，
仍走原进程组kill/wait/关pipe路径，不改变启动、配额、deadline或认证语义。

新增两项跨平台fake-only组合回归分别在stdout和stderr注入底层异常/短写，断言只尝试
一次写入、原4字节charge保留、实际文件为空、原kill/wait和sink关闭执行。
进程/selector/read/signal全部注入，只有临时文件为真实I/O；原Linux进程测试和JUnit桥
继续覆盖其原有人工子进程路径。未执行native probe、network clean-build或历史输入。

此修复关闭既有构建/JVM日志的失败重写缺口；不是跨进程配额交接或完整host实现。
可信Java host、全部artifact/JVM writer同根交接、完整filter/no-injection checker、复杂清理、
冻结fixture和整体最终门禁仍未齐备。整体原生验收尚未完成，研究结果及用户可见功能无新增。
仅test-only设施修复，生产架构、README/AGENTS、冻结协议/预算/guard无需改动。
