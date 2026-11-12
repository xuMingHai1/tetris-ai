# 固定动作回放：写入前累计配额组件

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
排空EOF，再关闭FD/sink；EOF本身不结算task，close失败不能返回drained成功。
未配置output的既有直接构造路径保持原调用方负责stdio的契约。

20项集成测试使用真实临时pipe/文件，但全部fork/wait/ptrace/pidfd/signal均为fake。
覆盖原双EXEC/精确ENTRY/typed wire/reap链、两个输出流、artifact争用、超额后清理、
不丢wait、末尾排空、ECHILD、期限、I/O/close失败及一次性factory；Linux普通JUnit
桥强制20项/零skip/30秒。它们不是原生能力或完整network clean-build验收。

有限剩余清单：可信Java宿主的启动/终态观察与三个writer的跨进程互斥交接；协调器
全部artifact/JVM保留额度与原来源/manifest冻结、完整filter/external no-injection checker；
剩余复杂cleanup和完整冻结fixture/task预留表、EXITKILL终末负例；最终整体Review/CI、
compatibility/inputhash、唯一登记和原执行前门。此接线没有完成上述项，不可把
进程内原ByteQuota的共享升级为Java/Python跨进程传输证明，不fork/pickle该对象。
没有native probe、network clean-build或历史22步执行；研究结果和用户可见功能无新增。
仅test-only设施，生产架构/README/AGENTS、预算/guard及全部停止门不变。

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
