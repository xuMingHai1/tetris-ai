# 固定动作回放：保存 journal 的报告装配

落实 [已接受设计](../design/heart-fixed-witness-replay.md) 的独立报告呈现范围。
本次新增修改只有 test-only `FixedWitnessReport`、人工测试和本说明；
不增加 main/collect、历史输入读取、supervisor 或新的研究执行。

`write` 使用调用方的输入、hash、路径与终态期望，通过现有
`FixedWitnessJournalReader` 读取保存的 journal，不执行动作或重新计算棋盘规则。
报告保存原始行、attempted/completed 与 journal 状态；结构校验失败时不呈现派生几何。
结构一致不保证所有指标真实，报告的 `RECORDED_GEOMETRY` 仅解释记录值，
终态与固定期望不一致仍保留 `EVIDENCE_MISMATCH`，不能当成完整诊断。

逐步输出 REQUIRED 缺口、FORBIDDEN 占用、non-FORBIDDEN holes、
超出生产 HEART 高度的行数、当前及累计绝对消行；每条路径只在其终态已记录时
报告 clean、固定期望是否一致、有无绝对消行及几何必要条件失败项。
未记录终态使用 `NOT_RECORDED`，不填补 false/0。
必要条件通过也只表示 `NOT_CERTIFIED`；不从 occupancy 推断颜色、tile 身份或 Q。
`provenanceAuthenticated`、`qCertified`、`eligibleForRuntimeValidation` 固定 false。
该组件不提供 raw stdout/stderr、构建来源或唯一执行认证；这些仍属完整监督链。

完整 JSON 的精确 UTF-8 字节（含 LF）在 `CREATE_NEW` 前一次性计入调用方提供的
`JournalQuota`，写入后 `force(true)`；失败创建/写入不返还额度，不覆盖旧产物。
调用方必须复用 journal 的同一实例，或提供同一父 root 已永久预留的另一实例。
没有默认/额外 10MiB；报告保留原始行也是新增副本，全部正常扣费。
组件不能证明未合作的 writer 或整个 session 已统一限额。

人工测试覆盖独立路径重置、完整/部分/INVALID/终态不符、结构损坏原行保留和
几何抑制、非零派生指标、报告一字节不足、journal/report 共用精确额度及失败不返还。
非零指标篡改例仅验证记录的呈现，不认证指标的生产真值。
不读取历史 CSV，不执行原22步、网络能力验收、search 或 population。

PR170 存储分类、PR174 累计 task 政策、网络/完整 supervisor/root 接线、
完整实现最终 Review/CI、兼容性/input hash 和唯一登记仍是后续门。
原16进程、10MiB、#140/GoalWindow、#95/#96、reserved/provider/runtime/default 边界不变。
这不改变生产架构或运行方式，README/AGENTS/ADR 无需修改；研究索引补入口。

## 本次新增修改：不可变报告请求

依照 [请求边界设计](../design/heart-fixed-witness-report-boundary.md)，
`FixedWitnessReportRequest` 构造时拥有输入与 journal 字节副本，验证各自登记 hash、
输入格式、路径及完整非空终态期望。请求不读取文件，不执行动作；
Reader 的 Path 入口与新增 byte[] 入口共用同一解析状态机。
Report 的快照入口消费同一批字节，不生成临时 journal。

请求声明按 schema/inputSha256/journalSha256/paths/expected 顺序编码，
路径保持调用方顺序，期望依路径及 Expected 七字段声明顺序编码；
精确 UTF-8 JSON 加单个 LF 的 SHA-256 是 requestSha256。
显式请求报告增加 requestIdentity（完整声明及 requestSha256），可从输出独立重算；
旧 writer 输出不增加此对象。规范字节查询返回新副本。
同一输入内容的期望 map 插入顺序不影响身份。

输出完整字节（包括身份）沿用原 CREATE_NEW/写前扣费/force/失败不退费规则；
调用者显式传入同根永久预留额度，本组件不创建根额度或证明传入额度的祖先。
人工测试仅直接构造保存事件，每条路径一步、最多两路径，共16项，
区分损坏前缀 INCOMPLETE 和合法 mismatch END，不读取历史 CSV 或执行回放。
该边界尚未接入普通 JVM，不修改双侧加载清单、命令或握手；
请求 hash 也不提供来源认证、Q、完整 session 或 runtime 权限。
