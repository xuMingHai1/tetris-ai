# AI 研究索引与采用边界

本页整理 Issue [#86](https://github.com/xuMingHai1/tetris-ai/issues/86) 的研究导航。基线为 `main@6090420`（PR #89 后）；运行时事实以源码为准。[运行指南](../../README.md) / [架构契约](../architecture/ai-engine.md) / [详细实验记录](ai-experiment-history.md)。

## 当前生产能力

| 配置 | 实际策略与限制 |
| --- | --- |
| 未配置 `TETRIS_AI_AGENT` | 本地 placement heuristic，经 adapter 生成 `AiPlan`；AI 开关由 F2 控制。 |
| `action` + 默认 `survival` | deterministic action-native SURVIVAL top-1。 |
| `action` + `tuck-hunter` | 在可达动作中按 objective risk/safety 约束偏好 tuck。 |
| `action` + `build-shape` | fixed HEART occupancy，单步 greedy；常规 creative 候选受 top-5、Risk Controller、相对 SURVIVAL top-1 Safety Budget 约束；DANGER 回退 top-1。 |
| 上一行再加 `TETRIS_AI_PREVIEW_RESCUE=true` | 显式 opt-in：普通 BUILD_SHAPE 选中 rank 1 且已知 preview 不可落下时，按既有 SURVIVAL 顺序取首个恢复 preview 的替代动作；无替代则保留原计划。每步重新从普通 BUILD_SHAPE 开始。它是独立 rescue 路径，不能当作常规 creative budget 的放宽。 |
| `jev` / `jev-action` | 显式启用远程 shortlist 选择；需要凭据，不能仅凭 key 自动开启。不支持 creative objective/Jev 指令创作。 |

颜色目标、自定义图案、自然语言/Jev 指令创作、稳定 clean HEART 构造均不是已交付能力。clean completion 必须在生产 post-row-clear 棋盘上同时满足全部 REQUIRED 与零 FORBIDDEN；SUPPORT_ALLOWED 中性，不能把全覆盖等同于完整图形。

PREVIEW_ONLY benchmark 调用生产 opt-in planner；STRICT 是独立的 frozen-warning 实验臂。[PR #66](https://github.com/xuMingHai1/tetris-ai/pull/66) 引入 opt-in，[PR #88](https://github.com/xuMingHai1/tetris-ai/pull/88) 对齐运行时文档。

策略选择事实：[AiPlanningAgentFactory](../../src/main/java/xyz/xuminghai/tetris/ai/AiPlanningAgentFactory.java)。

## 未接受的诊断实现

[#111 每步几何域诊断的审查记录](heart-prefix-diagnostic.md) 保留 0/20 witness 与固定源码出处；后续 #113 找回全部 23 份既有文件，登记 hash、逐 seed 记录和汇总通过只读日志完整性核验，构建来源及 seed 生成仍未认证。原实现 #112 因冻结目录冲突不予合并，最终只接受归档与核验工具；保持既有 guard / v1.1，停止该域的在线推进。不重跑或放宽原域；该诊断本身不解锁在线任务。#116 后仅 #94 的新 GoalWindow 测试原型获准，#95/#96 继续停止。

## 终态证书诊断（完成，固定预算 miss）

[GoalWindow机理复核](heart-goal-window-mechanism.md)保留原停止结论。[终态分解与落子顺序证书](../design/heart-terminal-certificate.md)的一次登记诊断已于2026-11-02执行：[结果及来源](heart-terminal-certificate-result.md)。共享2,000次生产枚举耗尽，27个分解、67个几何节点，没有完整order或Q证书；BOUNDED_MISS不是无解证明。停止该假设，不加预算/换排序/重跑；后续先只读复核既有证据，新假设另经设计审查。#95/#96、population、reserved/provider/runtime继续停止。

## 固定旧 witness 的结构核验

[离线登记 manifest 与一次性占位设计](../design/heart-fixed-witness-replay-registration.md)
由[PR189](https://github.com/xuMingHai1/tetris-ai/pull/189)接受；组件
`fixed_witness_replay_registration.py`实现有界规范metadata、独立receipt、同逻辑诊断
永久独占占位及失败前缀保留，Linux桥接固定8项纯人工metadata测试。
INTENT_RETAINED不代表执行许可；不创建真实历史session，不解除170/174/173及整体执行门。

[单 JVM 固定动作认证回放与报告接线设计](../design/heart-fixed-witness-replay-execution.md)
比较仅嵌入式、双JVM与单JVM接线，提出执行前父权威请求和执行后journal/报告绑定。
设计已由 [PR183](https://github.com/xuMingHai1/tetris-ai/pull/183) 合并接受；
`FixedWitnessReplayRequest` 独立组件提供执行前动作请求的自有字节、规范声明/hash与严格解析，
不含journal摘要，不读文件或执行动作。实现验证使用原16项预算中的前3项人工案例；
`FixedWitnessReplayDescriptor` 另提供恰好两文件私有目录的 metadata-only 检查、
有界 NOFOLLOW 自有读取和保留身份复核；调用者仍须独占目录并排除硬链接别名 writer。
其单项人工快照/原件漂移案例占原预算第4项；Windows 缺少 fileKey 时跳过不算执行。
Python父端[保存证据组件](heart-fixed-witness-replay-evidence.md#执行证据的父端结构核验)另提供
两文件不可变receipt、同root原子H/J/D/R预扣和精确五文件退出核验；
只检查保存结构，不启动或认证JVM，不执行动作。新增案例仅使用原矩阵13/15/16，
与请求/快照组件前4项合计7项。
`FixedWitnessReplayExecutionApplication` 提供显式 `--replay-fixed` 普通 JVM 入口：
READY 前仅看 metadata，绑定 ACK 后唯一回放，关闭 journal 后通过原 ReportRequest 写规范声明与报告，
最后写出携带实际 journalState 的回执。四个合作 writer 先扣费、独占创建并 force；失败保留前缀，
不补 END、不重试动作。双侧新增独立 execution 加载清单，旧两个模式清单不变。
新增五项人工案例只占原矩阵第8/10/11/12/14项（argv拒绝、INVALID、MISMATCH、journal耗尽、报告写失败）。
专用 `scripts/fixed_witness_replay_execution.py` 接线父端 build/request/proc/加载身份认证、
保留原 READY 字节、主监督线程的唯一 ACK/EOF 与退出五文件核验；
`REPLAY_REPORTED` 仅表示传输闭合，并保留实际 journal_state，不提升 INVALID/MISMATCH。
启动前同 root 原子永久预扣 H/J/D/R，新增副本和日志仍另行计费。
最后第5/6/7/9项覆盖清单兼容、READY 摘要漂移、真实 EOF 拒绝和单 JVM 人工成功路径；
原16项总预算不重置。Linux桥接使用已有编译目录和 mock build receipt，
不等同 clean-build/网络来源或历史验收；Windows 平台 skip 不算执行。
该组件不提供历史输入或完整网络验收许可，
不解除170/174及原16进程/10MiB、唯一登记与任何停止/采用门。

[诊断 JVM 网络拒绝设计](heart-fixed-witness-replay-network.md)比较启动前 OS 机制，提出保持普通 JVM PID/argv/握手的 seccomp 方案及有界人工反例验收；当前仅设计候选，尚无网络隔离实测或历史执行许可。

[普通 JVM 观测与握手组件](heart-fixed-witness-replay-jvm.md)提供无回放的verify-only入口，
在READY后等待父认证ACK，记录相关class实际来源/hash并检查漂移；
整体来源认证、网络限制、总预算与唯一登记尚待集成，不提供历史22步执行许可。

[固定既有动作回放设计](../design/heart-fixed-witness-replay.md)接续 #91 恢复的两条旧路径，提议仅在测试源码中回放固定22步，核对绝对消行与逐步post-clear棋盘；不重新生成seed、枚举或搜索。设计及实现分别完成最终独立Review/适用CI/正常合并后，才可登记唯一诊断；当前尚无新回放结果。它不复活终态证书或GoalWindow，不放宽Q/guard，不解锁#95/#96或runtime。

设计已由 #155 接受；[输入与静态兼容性](heart-fixed-witness-replay-input.md)登记原22行的来源/hash、严格 test-only reader 和历史源码变化。[回放与逐步证据实现](heart-fixed-witness-replay-evidence.md)增加生产动作重放、独占 journal 和不重放动作的结构检查器；[报告装配](heart-fixed-witness-replay-report.md)从保存记录呈现逐步几何与路径终态，写前共享预留额度。仍缺完整父监督、构建来源认证及唯一登记，没有完整历史回放结果或执行许可。完整历史输入不进入 synthetic/CI 诊断。

[仅写报告的JVM父认证设计](../design/heart-fixed-witness-report-jvm.md)接续PR180描述符组件，
定义独立请求receipt、READY/ACK/加载字节、同根永久额度与报告传输终态；PR181已接受设计，
[报告组件说明](heart-fixed-witness-replay-report.md)记录独立test-only实现及人工验证边界。
报告传输不解除170/174、完整历史/网络验收及采用门。

[父权威描述符与受控快照设计](../design/heart-fixed-witness-report-descriptor.md)接续已交付请求，
定义严格规范JSON、独立期望摘要与有界文件快照；PR179已接受设计、PR180已交付组件，
不提供JVM报告/历史执行资格。

[报告请求边界设计](../design/heart-fixed-witness-report-boundary.md)比较直接增加JVM命令与先绑定不可变请求，
独立test-only字节快照/请求身份设计由PR177接受、组件由PR178交付，仅允许人工验证，
请求组件本身不改变verify-only或开放命令；report-only接线由PR181单独设计，均不执行历史动作。

[固定回放进程监督组件](heart-fixed-witness-replay-process.md)增加有界输出、timeout/中断和进程组清理的人工验证；[累计配额组件](heart-fixed-witness-replay-quota.md)提供合作式writer的共享写入前计费与管道接线，所有产物writer的统一接线及唯一登记仍未完成，不执行历史22步。

[固定回放字节身份组件](heart-fixed-witness-replay-identity.md)绑定源码、编译资源和有序依赖字节，拒绝漂移与class shadow；尚未整合构建/加载观测/整体监督，不提供历史回放执行许可。

[独立构建准备组件](heart-fixed-witness-replay-prepare.md)接通受控环境、实际Java/Maven版本观测和clean Wrapper构建的前后字节绑定；人工子进程不运行Java或历史22步，整体诊断来源/预算与唯一登记仍待完成。

## 已完成实验

以下协议是**历史结果所用的固定样本**，不是所有入口的当前默认值。`g×p` 表示局数×piece limit；同 seed 保证 7-bag 序列，不保证 JavaFX 时序。表中入口类位于 `xyz.xuminghai.tetris.ai.benchmark`，可从 [源码目录](../../src/main/java/xyz/xuminghai/tetris/ai/benchmark) 查阅。目的、关键结果和采用结论逐行列出；完整观测值与局限见 [详细记录](ai-experiment-history.md)。

| 实验目的 / 状态 | Workflow mode / 入口 | 固定协议 | 关键结果 / 采用结论 | PR 证据 |
| --- | --- | --- | --- | --- |
| Full reachable creative envelope：完成，否决 | `compare-shape` / `BenchmarkApplication` | 对照协议见 PR #39/#40，保留旧 revision 复现 | 平均 considered ~5→reachable ~23，几乎没有新增 safety-eligible freedom，clean 无改善且 survival 回归；恢复 top-5。 | [#39](https://github.com/xuMingHai1/tetris-ai/pull/39)、[#40](https://github.com/xuMingHai1/tetris-ai/pull/40) |
| 一步 preview lookahead：完成，未采用 | `compare-shape-preview` / `BenchmarkApplication` | seed 1000–1019，20×500 | error 20.386→20.002；horizon 19→18/20；clean 0。不增加 runtime 深度。 | [#41](https://github.com/xuMingHai1/tetris-ai/pull/41) |
| HEART 可构造性：完成，证实存在 witness | `shape-feasibility` / `ShapeFeasibilityApplication` | seed 1000–1019，depth 24 / beam 128 | seed 1001/12 块、1008/10 块 clean；bounded miss 不证明不可能，search 不直接采用。 | [#43](https://github.com/xuMingHai1/tetris-ai/pull/43) |
| Witness runtime blocker / hole 分类：完成，诊断 | 同 feasibility / `ShapeWitnessConstraintAudit` | 两个 clean witness，22 步 | 19 步 top-5 外、17 DANGER、22 raw budget 拒绝；只排除 FORBIDDEN hole 仍 0/22 通过。不能只放宽一个阈值。 | [#44](https://github.com/xuMingHai1/tetris-ai/pull/44)、[#45](https://github.com/xuMingHai1/tetris-ai/pull/45) |
| Construction envelope / viability：完成，诊断 | 同 feasibility / `ConstructionSafetyEnvelopeBenchmark`、`ShapeConstructionViabilityBenchmark` | 22 witness states；search depth 4 / beam 32；greedy 24 | 22/22 search 到 4，20/22 greedy 到 24，21/22 不弱于 baseline。Observed reserve 不是阈值。 | [#46](https://github.com/xuMingHai1/tetris-ai/pull/46)、[#48](https://github.com/xuMingHai1/tetris-ai/pull/48) |
| Preview count guard：完成，否决 | `compare-shape-guard` / `BenchmarkApplication` | seed 1000–1009，10×250，三 retention 档 | runtime 10/10 到 250，guard 3/2/2；error 改善但 clean 0。不微调比例挽救 runtime safety。 | [#49](https://github.com/xuMingHai1/tetris-ai/pull/49) |
| Recovery facts 分离：完成，校准 | `recovery-robustness` / `RecoveryRobustnessApplication` | 同 10×250；22 clean / 35 failed-tail，末 5 placement | clean reserve 最低 8，failed 最多 2；count/holes 重叠。8/2 不直接采用。 | [#50](https://github.com/xuMingHai1/tetris-ai/pull/50) |
| Warning lead-time：完成，校准 | `recovery-reserve-lead-time` / `RecoveryReserveLeadTimeApplication` | 同 10×250；失败末 30，healthy 每 5+末 30；cutoff 2/4/6/8 | cutoff 2：clean 0/22、healthy 4/740、失败 7/7；lead-time 6–29。不能在同 seed 定 runtime composite guard。 | [#51](https://github.com/xuMingHai1/tetris-ai/pull/51) |
| Frozen signal 独立验证：完成，诊断 | `recovery-reserve-validation` / `RecoveryReserveValidationApplication` | seed 2000–2019；healthy 1000 / guard failure 500 | clean 0/22、healthy 0/3360、失败 16/16；还未证明 production failures。 | [#52](https://github.com/xuMingHai1/tetris-ai/pull/52) |
| Production failures：完成，诊断 | `recovery-reserve-runtime-validation` / `RecoveryReserveRuntimeValidationApplication` | seed 3000–3039，40×1000 | healthy 28、failed 12；healthy 7/6272 warnings；12/12 detected，但最小 lead-time 0。 | [#53](https://github.com/xuMingHai1/tetris-ai/pull/53) |
| Warning 时间结构：完成，否决简单 persistence | `recovery-reserve-persistence-validation` / `RecoveryReservePersistenceValidationApplication` | seed 4000–4029，30×1000，每 placement | healthy 可恢复 15-step episode；failed recovered 可 18，terminal 最短 1。连续 N warning 不能作介入条件。 | [#54](https://github.com/xuMingHai1/tetris-ai/pull/54) |
| Warning→SURVIVAL fallback：完成，否决 | `recovery-intervention-comparison` / `RecoveryInterventionComparisonApplication` | seed 5000–5029，30×1000 paired | 30/30 tied；274/274 已是 rank1，replacement 0，只增加 probe 成本。 | [#55](https://github.com/xuMingHai1/tetris-ai/pull/55) |
| Recovery alternative availability：完成，诊断 | `recovery-candidate-scan` / `RecoveryCandidateScanApplication` | seed 6000–6019，20×1000 | 48/137 有 clearing alternative；scan 平均 45.793/max 143.043 ms。不直接用全量 scan 做 runtime。 | [#56](https://github.com/xuMingHai1/tetris-ai/pull/56) |
| First-clearing 整局收益：完成，否决直接采用 | `recovery-clearing-planner-comparison` / `RecoveryClearingPlannerComparisonApplication` | seed 7000–7029，30×1000 paired | horizon 14→16/30；12 improve/5 regress，总 +625，最差 -614，2 survivor 被打死；rank cap 不足。 | [#57](https://github.com/xuMingHai1/tetris-ai/pull/57) |
| One-shot 因果标签：完成，未形成 eligibility | `recovery-one-shot-counterfactual` / `RecoveryOneShotCounterfactualApplication` | seed 8000–8019，20×1000，continuation 50 | 52 samples：22 improved/17 regressed/13 tied；最终 healthy 组 0 improve。未来结果只作 evaluation label。 | [#58](https://github.com/xuMingHai1/tetris-ai/pull/58) |
| Pre-outcome feature audit：完成，未采用阈值 | `recovery-eligibility-feature-audit` / `RecoveryEligibilityFeatureAuditApplication` | seed 9000–9029，30×1000，continuation 50 | 81：27 helpful/24 harmful/30 tied；最好 numeric AUC≈0.60。需新 discriminator 与 fresh validation。 | [#59](https://github.com/xuMingHai1/tetris-ai/pull/59)、[#77](https://github.com/xuMingHai1/tetris-ai/pull/77) |
| Fatal preview rescue：完成，整局对照 | `preview-rescue-paired` / `PreviewRescuePairedApplication` | seed 10000–10119，120×1000，baseline/STRICT | 6 improved/0 regress，+203；8 replacement。旧 5/5 one-shot 是定义必然，不是 learned discriminator。 | [#60](https://github.com/xuMingHai1/tetris-ai/pull/60)、[#61](https://github.com/xuMingHai1/tetris-ai/pull/61) |
| Rescue eligibility / lightweight scan：完成 | 同 paired 入口 | seed 11000–11119，120×1000，baseline/STRICT/PREVIEW_ONLY | horizon 85/88/90；PREVIEW_ONLY 27 improve/0 regress，+2231。轻量 scan 保持非计时 CSV 一致。 | [#63](https://github.com/xuMingHai1/tetris-ai/pull/63)、[#64](https://github.com/xuMingHai1/tetris-ai/pull/64) |
| Rescue tail latency / opt-in equivalence：完成，仅显式 opt-in | 同 paired 入口（当前默认 seed 12000） | seed 12000–12119，120×1000，三臂；nearest-rank P95 | horizon 71/73/74；PREVIEW_ONLY 25 improve/0 regress，+486；#65 search P95 0.765/max 0.862 ms，#66 非计时 CSV 完全一致。不能推导 Windows deadline 或默认采用。 | [#65](https://github.com/xuMingHai1/tetris-ai/pull/65)、[#66](https://github.com/xuMingHai1/tetris-ai/pull/66) |

Frozen recovery warning 始终是：preview 不可恢复，或可恢复但 `recoveryHeadroom<=2 && recoveryHoles>=15`。它是实验诊断标签；没有作为普通 runtime recovery guard 采用。新 safety policy 必须独立于通用 SURVIVAL/TUCK_HUNTER budget。

## 常用评估入口

通用 survival / objective 评估仍保留在 [运行指南](../../README.md#ai-benchmark)：`BenchmarkApplication` 支持 heuristic、lookahead、action、tuck-hunter、adaptive-tuck-hunter 及 shape 对照；`ReachabilityBenchmarkApplication` 对照 placement 与 action-native 可达性，`action-provenance` 记录 action-only outcomes。这些入口也不能建立第二套游戏规则或把遥测反馈到策略。手动 Jev/compare 模式会消耗 provider quota，必须先单独确认成本与凭据；本次整理没有执行它们。

## 复现与证据保存

- [手动/reusable workflow](../../.github/workflows/ai-benchmark.yml) 是命令、输入验证和 artifact 收集的唯一维护处；[PR selector](../../.github/workflows/ai-benchmark-pr.yml) 决定优先级与自动固定参数。表中历史 seed 与当前默认值不同时，复现必须显式传入历史 seed、games、max_pieces。
- 普通实验使用 `games / max_pieces / seed`；one-shot 与 feature audit 的 continuation horizon 固定在源码常量 `CONTINUATION_HORIZON=50`，不是 workflow input，feasibility 独立指定 `feasibility_depth=24 / feasibility_beam_width=128` 及 viability search/greedy 参数。全部使用 Maven Wrapper；Windows 用 `mvnw.cmd`。
- 当前 preview paired 是三臂，PREVIEW_ONLY 已共用生产 planner。严格复现旧两臂结果应使用对应 PR 的 merge revision 与协议，不能把现行三臂输出冒充旧 run。
- 数据留作 Actions artifact，不提交生成 CSV。保留 per-decision/per-game、construction diagnostics、feasibility witness/audit、target-aware hole counterfactual、safety envelope/viability，以及每轮 recovery feature/episode 数据。准确文件清单以 workflow 收集配置为准。
- PR 页面保存协议、结果摘要与 Checks/Actions 导航。artifact 有保留期限（AI Benchmark 30 天、Desktop AI Timing 7 天），链接不等于永久可下载证据；失效后从冻结源码和协议重跑。计时受主机影响，不要求新 run 重现同毫秒数。
- 自动路径只运行本地 deterministic agent、同仓库 PR；不调用 Jev、不读 provider secret、不使用外部 fork 占 self-hosted runner。研究不重新实现碰撞、旋转、下落或消行。

## 稳定 HEART 阶段启动

[#91](https://github.com/xuMingHai1/tetris-ai/issues/91) 的第一项 [#92](https://github.com/xuMingHai1/tetris-ai/issues/92) 已通过 [#104](https://github.com/xuMingHai1/tetris-ai/pull/104) 合并接受：[HEART 验收协议 v1.1](heart-acceptance-v1.md) 与 [固定数值](heart-acceptance-v1.json) 保持冻结。自动 `heart-baseline` 只采集开发/校准的 80 局，独立 validation 暂不运行；基线事实与新候选的采用目标分开报告。

[#93 在线构造设计审查](../design/heart-online-construction.md) 已通过 [#106](https://github.com/xuMingHai1/tetris-ai/pull/106) 接受否决与停止结论：两块构造事务缺少中间损伤边界和累计存活依据。旧稿保留评审前叙述，最终接受状态以 #91 / PR 合并决议为准；这不是 HEART 不可构造的证明。[#105 paired 分析工具](heart-paired-interface.md) 已通过 [#108](https://github.com/xuMingHai1/tetris-ai/pull/108) 接受，工具完成不等于候选采用。

[#109 目标留白与每步几何域](../design/heart-prefix-domain.md) 交付诊断设计，接受状态以任务与 PR 的最终决议为准：核对 clean HEART 的至少 12 个 raw holes，提议先诊断无额外 hole、零 FORBIDDEN 侵入、限于 target 高度的前缀域是否可达。静态结构事实不是安全证书；该设计及原域诊断不自行解除 #94，其他候选须另行接受，见下方 #115。#95/#96 继续停止，独立/Windows population 未使用。

## 后续稳定 HEART 的验收维度

[构建存储与 10MiB 输出边界分析](heart-fixed-witness-replay-storage-boundary.md)盘点
HOME/TMP/target 外部写入及源码资源的实际字节下限；2026-11-09用户明确接受方案A，
prepare现将构建HOME/TMP与证据目录分离并记录分类，证据仍共用10MiB，构建无磁盘硬上限。
任务预算监督、诊断写隔离和完整验收仍待完成，不提供历史诊断许可。

[固定 ABI 探针身份设计](heart-fixed-witness-replay-abi-identity.md)细化两条已接受的
非创建入口例外、出生/exec绑定和一次性消费；尚未实现，当前native拒绝不变。

[#174累计任务政策](heart-fixed-witness-replay-task-budget-proposal.md)亦于2026-11-09
获用户明确接受；首个纯账本组件覆盖512尝试/128live/2M事件、pending与reap计费。
synthetic序列测试不证明ptrace创建前强制；真实syscall/ABI接线及整体验收仍待完成。

[出生身份与线程组元数据](heart-fixed-witness-replay-thread-groups.md)在创建入口固定
CLONE_THREAD 关系，区分组 birth 与数值 TGID，保留逐线程计费和 leader reap 后历史。
这是 exec 身份迁移的前置模型；它本身不能授权原生验收或历史回放。

[EXEC 身份账本迁移](heart-fixed-witness-replay-exec-ledger.md)区分当前 leader 的内核撤销
与实际终态 reap，保留原组 birth、逐次尝试计费和迁移后 TID；该账本本身仅为纯模型。

[EXEC 入口与事件匹配](heart-fixed-witness-replay-exec-event.md)把旧 TID 的 native exec
入口绑定到同组 TGID 事件；现已在 post-handshake stop 接线完成账本/wire/等待身份
迁移和保守 resume，要求后续 native 零 EXIT。旧 TID ECHILD 只触发原期限内继续等待
实际 TGID 事件。仍无完整监督器、原生能力证明或历史执行许可。

[失败后终态回收](heart-fixed-witness-replay-cleanup-reap.md)在原 deadline 内回收已结算的
process births，保持各层失败锁存及共享事件计费；未决创建和线程身份歧义拒绝。
它不发送 kill、不恢复任务，也不构成完整整树清理或原生能力证明。

[普通信号递送](heart-fixed-witness-replay-signal-delivery.md)在 birth/GETSIGINFO 认证后
原号恢复非停止信号，保留真实 SIGSYS 和内核 siginfo；作业控制/group-stop 仍拒绝。
仅合成验证，不代表原生能力或完整监督器已通过验收。

[实际进程观测组件](heart-fixed-witness-replay-observation.md)读取Linux实际argv/环境/executable/cwd与
进程身份，人工子进程验证拒绝漂移；尚无JVM握手、loaded-class/网络证明或历史22步执行许可。

[父进程 READY 认证组件](heart-fixed-witness-replay-admission.md)将独立 receipt、真实/proc 与
loaded-class/hash 观察绑定后返回 ACK 字节；尚未发送到 JVM，也未完成网络/总配额/整体监督。

[verify-only 父管道组件](heart-fixed-witness-replay-pipe.md)在监督中认证READY、发送ACK/EOF并
复核退出后证据；网络限制、累计artifact配额、整体监督与历史回放门仍未完成。

[#115 无状态终局窗口设计评审](../design/heart-goal-window.md) 审查新的精确终局选择例外：非终态 setup 保留原 top-5/risk/budget，仅对当前或已知 preview 的真实 clean 终态使用事先冻结的几何条件。提议在正常测试源码中复用同包生产 API，保持 frozen source guard；不是继续原逐前缀域或把目标 holes 当安全证书。若经独立评审并合并接受，只允许 #94 的测试源码原型与开发/校准 paired 评估，#95/#96 继续停止；评审提交时尚无原型或新采集。实际接受状态以 issue/PR 最终决议为准。

以下是长期验收维度；本阶段具体数值已在上面的 v1.1 协议中预注册并通过 #92 接受，能力仍未达到，不能宣称稳定 HEART。现有 clean witness 只证明存在可构造路径。

| 维度 | 下一阶段需预先定义并提供的证据 |
| --- | --- |
| 构造完成率 | per-game clean completion（REQUIRED 全命中+FORBIDDEN 全空），time/pieces-to-clean；同时报告 best error、zero-forbidden coverage 和 full-required intrusion，不能用均值覆盖率代替完成率。 |
| 独立 population | 开发/校准 seed 与冻结后的 fresh validation seed 分离；同 seed paired baseline；预注册 games、piece horizon、target、search budget，保留每局结果与 horizon censoring。 |
| 存活代价 | 与当前生产 BUILD_SHAPE 比较 horizon rate、pieces 分布、逐 seed wins/losses、最坏回归和 baseline survivor 损失；不能只凭 aggregate 净收益采用。 |
| 决策与桌面延迟 | full decision 与检测/搜索的 P95/max，逐局最慢值和端到端应用/fallback/playback；headless 与真实 Windows JavaFX/重力期限验收分开。 |
| Runtime adoption gate | 冻结 eligibility/safety 语义后独立验证 construction improvement 与 survival regression；复用生产规则、snapshot/generation、人工优先、gravity fallback。默认采用是单独决策，opt-in 证据不自动提升默认。 |
| 新能力范围 | 颜色先有显式 color-aware settled state；自定义 target 先定义 REQUIRED/FORBIDDEN/SUPPORT 与合法边界；Jev 指令创作单独设计 deterministic objective facts、成本与授权，不能让远程模型定义游戏规则。 |

当前没有可直接采用的普通 frozen-warning recovery discriminator。不得继续从原 seed 调 rank cap、persistence N 或 guard 比例来“救活”已否决设计。若有新假设，先冻结可观测特征与协议，再收集独立证据。


[独立进程 pidfd 清理](heart-fixed-witness-replay-settled-cleanup.md)在原 owner/deadline
下先固定已认证的独立进程身份、发送 SIGKILL 再回收；已绑定创建事件的pending进程
可在父结果缺失时沿用该路径，子/父均只凭实际reap结算；未绑定创建/线程与未握手身份仍拒绝，
成功发送后仅原birth的排队signal/syscall stop可共享计费后继续等待，不resume；
仅合成验证，不代表完整整树清理或原生验收。

[vfork 组合监督验证](heart-fixed-witness-replay-vfork-session.md)串接 fake launch、
初始握手和统一 wait loop，覆盖父出口不可用时的子 exec/退出及原预算失败路径；
仅合成验证，不是原生 ptrace、完整清理或历史回放验收。

## GoalWindow 原型已测量，候选停止

#116 已接受设计；[#94 实施记录](heart-goal-window-prototype.md) 与 [session driver](goal-window-driver.md) 保留测试原型及完整接线说明。#137 后唯一登记的80配对实测已完成，双臂均0 clean，candidate全部68,706次决策为BASELINE_CHOICE；前500块clean与配对clean净收益两个门失败，候选停止，不重跑或调参。详见[实际结果与证据索引](heart-goal-window-result.md)。实现/预检成功不等于候选收益通过；#95/#96、reserved、provider与runtime/default adoption继续停止。
