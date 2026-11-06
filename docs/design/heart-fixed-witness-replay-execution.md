# 固定动作的单 JVM 认证回放与报告接线

状态：设计候选；最终 SHA 独立 Review、适用 CI 和正常合并前不接受实施。
跟踪 #91；接续已接受的 [固定回放设计](heart-fixed-witness-replay.md) 与
[报告 JVM 设计](heart-fixed-witness-report-jvm.md)。本稿无实现或执行结果。

## 当前缺口与范围

源码基线 main@e7e9ec360d136e247c999f96db175431f8b197d5（PR182合并后）。
`FixedWitnessReplay.run` 已有生产动作复用、强制逐事件 journal 和共享子额度，
但认证动作入口仍缺；PR182的独立report-only接线已经原负责人合并交付。
ReportRequest 绑定已有 journalSha256，不能在尚未产生 journal 时充当动作授权请求。
`run_process` 的 VERIFIED_NO_REPLAY / REPORT_WRITTEN_NO_REPLAY 均不能表示动作执行完成。
这些是刻意保留的集成缺口，不是已交付组件的缺陷。

| 方案 | 收益与代价 | 选择 |
| --- | --- | --- |
| 保留仅嵌入式 Replay.run | 最少接口，继续没有认证后的普通 JVM 动作入口 | 保留退路 |
| 单独的回放 JVM，退出后再起 report JVM | 复用报告传输，但增加 JVM、复制文件及跨阶段预算/身份义务 | 不推荐；不借接线扩张单诊断 JVM 契约 |
| 同一个 JVM 在 ACK 后回放，再用已有 Request 写报告 | 一个动作执行者和一条 journal；新增动作请求及模式，父端仍只核对事实 | 推荐 |
| 立即连接 clean-build、网络 launcher 与唯一历史 session | 能形成整体监督，但170/174政策和最终整体门尚未满足 | 本设计排除 |

实施从本设计合并后的新 default branch 开始；不继续PR182的已合并分支。
仅改正常同包 test-only 类型、专用 `scripts/fixed_witness_replay_execution.py` / 人工测试、
双侧显式模式清单、process 的封闭成功状态、组件说明和研究索引。
不改生产、历史 fixture、依赖/build/workflow/selector、网络 launcher、原 guard 或冻结协议。
不增加自动采集入口、历史默认参数、resource 静态读取或唯一登记器。

## 动作前的父端权威请求

新增 test-only `FixedWitnessReplayRequest`：自有 input byte[]、inputSha256、有序 paths、
精确 expected，均防御性复制；复用 Input.read 校验但不创建方块/棋盘、不调用 Replay.run。
expected 只复用既有 Expected 字段语义，精确覆盖 paths，不从 journal 或子端声明取得。
最多64KiB input、两路径、合计22 placements，保持 Input 的既有动作和行约束。

规范 UTF-8/LF JSON 字段顺序为 schema/inputSha256/paths/expected；schema 固定
`fixed-witness-replay-request-v1`。paths 及 expected 的顺序/字段顺序沿用 ReportRequest，
但**不包含 journalSha256**。请求 hash 是这些规范原字节的 SHA-256。
Java 是唯一规范序列化器；严格 Jackson3 UTF-8/重复键/精确字段/null/整数范围检查，
解析后复用本类型 canonicalBytes 与原件逐字节比较，不另写容错或第二套 canonicalizer。
跨语言只比解析对象和独立摘要，不由 Python 生成权威规范 JSON。
字面量序列化向量（不代表有效input摘要）：inputSha256为64个a、单路径71/1、
expected七字段按上述ReportRequest顺序均为0，紧凑JSON加单个LF，
SHA-256为 `943d2579419f808834f2e05b6b10542c09083cb26487b9e49e64ad0969bec971`。
该向量只验证声明字节，真正请求还必须通过原input SHA-256和Input.read验证。

父调用者独立保留规范声明原件/hash及 input 原件，提供独占无 writer 的私有目录：
恰好 request.json（≤16KiB）和 input.csv（≤64KiB）。既有 ReportDescriptor 的三文件契约不变。
新入口按其相同的绝对规范目录、无链接祖先、NOFOLLOW、普通文件、非空 fileKey、
大小/mtime/身份前后稳定约束读取；已有受控路径检查可以最小复用，不能改变旧契约。
硬链接别名无 writer 仍是调用者先决条件，不声称OS隔离；Windows fileKey 不可得则拒绝。
父 receipt 是不可变字节及目录/文件身份快照，启动前、ACK前和退出后复核。
新增副本、receipt持久化均在同root写前计费，既存文件不冒充新输出。

## 唯一动作入口与五文件产物

新增 `FixedWitnessReplayExecutionApplication`，只有显式 `--replay-fixed` 模式；
精确参数为 checkout、新输出目录、nonce、H/J/D/R 四个正整数额度、请求目录、独立请求hash。
nonce与所有摘要均为小写64位hex；严格整数不接受boolean/浮点/指数/溢出/补默认。
JDK27/Linux/ordinary classpath，固定 -Xmx256m/-XX:MaxMetaspaceSize=128m，
受控 HOME/TMP 和环境、无 agent/注入参数、实际 argv/executable/cwd/进程身份沿用父认证。
所有目录相互分离；每额度不超过原10MiB，H另≤2MiB、D≤16KiB；父先验证总额而非子端自报。

READY 前只看请求目录和两文件 metadata，不读取内容或执行动作。
READY 使用独立 schema `fixed-witness-replay-execution-v1`，绑定 nonce/request hash/H/J/D/R、
显式 execution 模式观察、actions_started=false、diagnostic_authorized=false、
provenance_authenticated=false、eligible_for_runtime_validation=false。
父以独立 build receipt、请求 receipt、实际 /proc 和双侧加载字节认证后，
只有 process 监督主线程发送绑定 READY 原字节摘要的 ACK/EOF；EOF/错ACK/超时不得有 journal。
本组件没有网络隔离证明，network_isolation_verified 始终 false，父认证不提供历史执行许可。

ACK 后再次验证metadata/加载身份并读取唯一自有请求快照，校验期望hash。
只调用一次 Replay.run，按原请求顺序从空棋盘推进；不复制旋转/消行、枚举或使用 planner。
journal 保持 START/ATTEMPT/STEP/END 与 force、失败即停规则。
随后读取自己的有界 journal 快照，用已有 ReportRequest/Report/Reader/Checker 产生报告，
绝不第二次执行动作。额外写出该 ReportRequest 的 canonicalBytes，供父端绑定衍生报告身份。
诊断和报告合计仍只有一个直接 JVM；不启动另一 report JVM、子JVM或其他动作执行者。

成功退出后的精确允许集合是 ready.json、journal.jsonl、report-request.json、report.json、
replayed.json；不复用 verify-only 或 report-only 的允许集合/schema/加载清单。
report-request.json 是执行后由既有 Request 产生的声明（≤D），其 journalSha256 在执行前未知。
父从自己保留的 input/hash/paths/expected 与实际 journal 原字节hash构造**期望解析对象**，
比较该声明；只 hash 原字节，不在 Python 重新规范序列化。报告 requestIdentity 须与该对象及
声明原字节hash相符。因此 journal hash 可在执行后产生，动作输入权威仍来自执行前 receipt。

replayed.json 绑定 READY hash、原动作请求hash、journal/hash长度、报告声明/hash长度、
report/hash长度、四额度、actual journalState、actions_started，以及始终 false 的三权限字段。
actions_started=true 仅表示已进入动作执行阶段，不是COMPLETE、历史登记或来源认证。
进入阶段后即使第一次动作失败也不回退为 false；无ACK、内容拒绝或启动前失败保持false。
该阶段标记不提供精确动作次数；动作attempt/completed只从实际 journal 派生。

READY/replayed严格精确字段及类型、各≤1MiB且合计≤H；未列字段/null/重复键/额外文件拒绝。
ACK前后、报告装配前及最终回执写出前复核输入metadata和加载身份；父退出后再复核。
父退出后以NOFOLLOW有界稳定读取核验精确五文件、摘要/长度/额度、请求与前后身份，
检查 START 的 input/paths/expected 对应独立动作请求，END/计数对应报告，
journal_state只能从已核验实际report传播，并与journal END和replayed精确一致；不由exit0决定，
只复用/检查保存结构，不重新实现动作规则或再回放。
Java/Python 增加同名 execution 模式清单及具名嵌套类；保留两个旧模式字节清单。
加载观察不初始化类，观察到 Replay.class 不表示执行过动作。

## 状态、失败前缀与额度

process 只增加封闭成功状态 `REPLAY_REPORTED`；由专用父封装固定，
必须有 handshake 且 finalize 返回同一状态。它仅表示回放输出/报告传输闭合，
还须呈现独立 journal_state。INVALID/EVIDENCE_MISMATCH 不得冒充诊断成功或 BOUNDED_MISS。
只有保存结构为 COMPLETE 且所有整体来源/网络/预算/唯一登记门另行满足，未来整体监督才可
宣布历史诊断完成；本组件始终 diagnostic_authorized=false，不产生该整体声明。

Replay.run 的 IOException 保留已force前缀、非零退出，不补END、不重试，不为报告制造INVALID。
合法INVALID/EVIDENCE_MISMATCH END可以写同态报告，传输完成不提升其状态；
结构缺失、journal/report漂移、超限、取消、OOM、超时或非零退出均为父监督明确失败。
报告或最终回执写失败保留全部已写文件和原始错误，不清理/覆盖原件，不再次动作执行。
关闭 journal 后才装配报告；报告装配失败不能撤销已发生的attempt，父保留前缀和已扣额度。

沿用一个父 ByteQuota/OutputBudget 与原绝对总deadline。启动前原子永久预扣 H+J+D+R，
不足则不启动；Java H用于两个握手文件，J用于 journal，D用于规范报告声明，R用于报告。
每个writer仍先完整序列化/扣费再CREATE_NEW/force，失败/未用额度不退。
父端读不重复计费；新增任何持久化副本/日志/失败记录另扣同root。
不能把各单文件上限相加后声称独立10MiB；HOME/TMP/target仍不排除，
本组件只约束合作writer，不能称整个clean-build磁盘已受控或采用170存储分类。

## 有限实现验证与停止条件

最多16项新增人工案例，参数化子例也计数；每fixture最多两路径、每路径一步、
人工非历史ID，不读历史CSV、不用生成器、搜索/continuation或历史22步。
已有回放规则和管道超时/取消/洪泛测试不复制或重复计入新预算。

1. 规范请求字面量独立hash向量（1）、重复键（1）、非整数/溢出选定一个反例（1）。
2. 请求自有快照与父原件漂移（1）、执行模式双侧清单/旧模式兼容（1）。
3. READY hash不符（1）、无ACK/EOF不创建journal（1）、真实argv不符（1）。
4. Linux现有编译目录单JVM人工成功联调（1）、合法动作失败INVALID（1）、期望不符MISMATCH（1）。
5. journal额度不足保留前缀且不重复动作（1）、同root不足启动前拒绝（1）。
6. 报告装配写失败保留journal且不退费（1）、退出后journal字节漂移（1）、终态/报告状态冲突（1）。

成功联调明确使用mock build receipt，不等于clean-build/网络来源认证或历史验收。
完整Linux普通JVM用例须真实运行；Windows skip不算执行，不降低fileKey要求。
其他精确键/字段、输出集合/各额度和旧模式拒绝分支由静态Review检查，不宣称测试穷尽。
执行前登记精确命令及绝对截止：定向Wrapper≤120秒、Python≤30秒、完整verify≤600秒。
单JVM≤120秒、父阶段≤180秒、总命令≤900秒，共用绝对时钟并保留清理时间；
没有warmup、population、网络探针、历史输入或provider调用。
失败先定位，不能扩大16项、切环境反复执行同一诊断、增加预算或修改原协议来救活结果。

设计最终独立Review/适用CI/正常合并后只接受上述实施范围；实现最终revision同样需Review/CI。
新模式实施及人工测试不执行完整 prepare+network+历史链，原16进程界不因此改变。
若无法在该职责内完成、或必须改生产/冻结/网络/存储/task政策，则停该部分并记录具体依赖。
下一整体监督仍等待170/174明确具体接受、173实际能力门、完整实现Review/CI、
兼容性/inputhash刷新和唯一登记。#140 BOUNDED_MISS/GoalWindow停止、#95/#96、
reserved/provider/runtime/default门不变。生产架构/运行方式不变，README/AGENTS/ADR无需修改。
