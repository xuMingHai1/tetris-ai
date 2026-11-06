# 固定 witness：不执行动作的报告请求边界

状态：待最终 SHA 独立 Review、适用 CI 和正常合并的设计候选；本稿没有实现或运行结果。
接续 [#155 固定回放设计](heart-fixed-witness-replay.md) 与
[已交付报告组件](../research/heart-fixed-witness-replay-report.md)，总任务 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。
源码基线：`main@68c8558deee9127da19213bfaa4b43cde8325044`。

## 问题、证据与选择

PR176 的 `FixedWitnessReport.write` 已能从保存 journal 呈现结构与几何必要条件，
但调用方还必须独立提供输入、路径、终态期望和永久预留配额。
当前 `FixedWitnessReplayApplication` 仅接受 `--verify-only`；
Python `VerifyOnlyHandshake.finalize` 只允许 ready.json / verified.json。
Java `FixedWitnessJvmObservation.TEST` 与 Python `fixed_witness_replay_admission.TEST`
都未列入 `FixedWitnessReport`。因此现有握手成功不能认证报告执行，也没有报告请求身份。
这些是尚未接线的职责，不是现有 verify-only 的缺陷。

| 方案 | 收益与代价 | 决定 |
| --- | --- | --- |
| 保持独立 writer | 最小改动；每个未来调用者自行保证请求、字节与额度绑定 | 保留退路，但重复承担同一失败边界 |
| 先实现可嵌入的 report-only 请求组件 | 封装权威输入快照、请求身份、独占输出与额度；可独立用人工字节验证 | 推荐，作为下一独立实现里程碑 |
| 现在增加普通 JVM CLI / 父监督报告模式 | 可串起真实报告进程，但还须改变命令、握手、产物集合及整体资源监督 | 延后；本设计不批准该模式或完整 supervisor |

选择第二条，而非先把两次函数调用包装成命令。组件负责不可变请求及实际消费字节的绑定，
区别于已有 writer 的报告呈现职责。未来 JVM 集成必须先使用该边界，再另行完成父认证接线；
本次不让 verify-only 隐式读取输入、消费 journal 或执行动作。

## 请求与字节所有权

拟新增同包 test-only `FixedWitnessReportRequest`，不提供 main、环境开关或默认 fixture。
调用者显式提供原输入 byte[]、登记 input SHA-256、有序 PathSpec、完整 Expected map，
以及保存 journal 的精确 byte[] 和独立登记 journal SHA-256。
构造时防御复制字节、列表和 map，校验规范小写64位hash、输入格式、路径唯一、
期望键恰好等于路径集合、所有期望值非null且满足既有构造约束。
输入沿用64KiB/最多2路径22步，journal沿用10MiB；不从 START 反推任何权威字段，
不从动作产生终态期望，不读取历史资源，也不生成 piece/bag/seed。

请求身份为固定 schema `fixed-witness-report-request-v1` 的规范 JSON UTF-8/LF 字节的 SHA-256：
固定键序依次为 schema、inputSha256、journalSha256、paths、expected；
paths 保持调用方顺序，expected 同序，每项按 `FixedWitnessReportChecker.Expected` 的
七字段声明顺序写出整数；不允许额外字段、重复键、null、浮点数或省略值。
这里只生成身份，不开放 JSON 反序列化入口。相同期望的 map 插入顺序不应改变请求身份；
路径顺序、任一hash或期望改变必须改变身份。报告输出位置不属于请求内容，独占写入单独校验。
该hash标识调用者声明与实际字节，不证明调用者可信或历史来源真实。

为避免 hash 后重新读文件的窗口，为 `FixedWitnessJournalReader` 增加有界 byte[] 消费入口，
既有 Path 入口保留有界读取并委托它；`FixedWitnessReport` 同样增加使用该字节快照的内部入口。
不得把快照先写到临时 journal 再重读，也不得改现有尾行、严格字段、计数或状态语义。
读取路径、symlink/目录约束及前后文件漂移属于未来父监督，不由该纯字节组件冒充完成。

## 输出、额度与失败

请求组件只调用现有独立检查和报告呈现链，不调用 `FixedWitnessReplay.run`、
`ActionPlanSimulator`、候选枚举、planner、audit 或 Toolkit；引用 Row 类型不等于执行回放。
报告增加单一 `requestIdentity` 对象，保存 schema、request SHA-256、input/journal SHA-256，
以及路径和期望的完整规范声明，便于事后从输出重算请求hash；已有字段语义保持。
旧 writer 的已有调用可维持不含该对象的输出，只有显式请求入口增加它。
`provenanceAuthenticated`、`qCertified`、`eligibleForRuntimeValidation` 仍固定 false。

执行必须显式传 `JournalQuota`，无默认额度。父调用者须用 journal 的同一实例，
或同一父 root 已永久扣除的子额度；请求对象不创建根额度、不回收失败/未用额度。
含 requestIdentity 的完整报告 UTF-8/LF 字节在 CREATE_NEW 前一次性扣费，force(true)；
原文件/部分文件保持，失败不覆盖、不删除、不重试。输入/journal 的内存读取不重复算磁盘输出，
任何新增保存副本必须另计；本组件不创建副本或日志。
跨调用不能证明调用者传入的是同根额度，完整父监督仍必须证明预留关系。

| 条件 | 行为及解释 |
| --- | --- |
| 请求hash/输入/期望键或值非法 | 在创建输出前拒绝；不猜默认值，无报告成功 |
| journal完整记录格式非法、计数非法、END后字节 | 沿用reader拒绝，保留输入字节；不伪造INVALID报告 |
| 合法journal状态为INCOMPLETE / INVALID / EVIDENCE_MISMATCH | 如实输出相同状态；报告写成不等于诊断完成 |
| 结构/守恒不符但reader返回记录 | 保留原行及reader原状态，抑制派生几何；未终结前缀仍为INCOMPLETE，合法END声明不符时才为EVIDENCE_MISMATCH |
| 已有输出、配额不足或I/O失败 | 原样失败且不返还已扣费；由未来父层记录具体停止原因 |

没有必要条件失败仍为 NOT_CERTIFIED；不把报告hash、clean或写入成功升级为Q、
build认证、唯一session、BOUNDED_MISS或runtime资格。

## 实现与有限人工验证门

实现仅限上述请求类型、reader/writer字节入口及相应同包人工测试和组件说明；
不改 src/main、历史fixture、构建、workflow/selector、原guard、冻结协议或JVM命令。
尚不改Java/Python加载清单：未被普通JVM调用的组件加入清单并不能证明执行绑定。
未来真正接通时必须同步纳入 Report、ReportRequest 及其具名嵌套类型，
核对实际无初始化观察、receipt、ordered classpath/重复资源、READY/ACK和退出后漂移；
不得只在一侧添加名字或把 verify-only 的成功用作报告认证。

实现先经最终独立Review、适用CI和正常合并；这里只授权以下合成验证，
不触发历史执行登记或新研究采集：

- 每个fixture最多2条路径、每路径1步，使用既有人工journal模式及不同于历史的路径ID；
  请求组件测试不执行动作、不读取22行CSV，新增synthetic用例最多16项。
- 覆盖构造后修改原数组/list/map、map顺序稳定与请求字段变化、缺失/多余期望、
  hash不符、Path/byte入口一致、损坏/截断/终态不符、原行保留与几何抑制，
  特别区分损坏STEP后无END的INCOMPLETE与合法END的EVIDENCE_MISMATCH。
- 覆盖同一quota连续写入、含身份对象的精确字节限额、一字节不足、CREATE_NEW失败不退费，
  以及可从输出声明重新计算请求hash；不得只测试getter。
- 单次定向Wrapper测试最多120秒、完整verify最多600秒；执行前登记本地命令和截止时间，
  失败先定位，不通过追加fixture/预算或反复绿色重跑充当研究结果。
  这些是开发检查上限，不改变任何历史/网络验收的进程、存储或墙钟契约。

成功条件是请求实际消费同一快照、身份可复核、失败分类不变、输出共享额度且零动作执行。
发现必须修改生产/冻结语义或必须依赖完整网络验收才能验证的部分则停止该部分，
保留可独立组件；不静默扩大本设计。不因反例失败修改期望/历史输入让报告吻合。

## 后续门与文档影响

PR170存储分类、PR174累计task政策仍待明确接受；原16进程/10MiB口径继续有效，
不排除HOME/TMP/target。本设计不采用其候选，不执行完整clean-build网络人工验收。
实际普通JVM report-only模式、受控输入文件与父权威receipt绑定、网络隔离、整体配额和
900/600/180/120秒监督必须另经最终整体Review/CI；历史回放还须兼容性/inputhash刷新和唯一登记。
不能用本次请求hash或synthetic测试替代这些门。

#140的BOUNDED_MISS、GoalWindow停止，#95/#96、reserved/provider/runtime/default边界保持。
只新增测试组件的责任边界，不改变生产架构/游戏运行方式；研究索引增加入口，
README/AGENTS/ADR无需改动。设计合并仅接受上述独立实现范围，项目仍ACTIVE。
