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

## 下一构造方向（Pending）

[GoalWindow机理复核](heart-goal-window-mechanism.md)只读复算既有raw：搜索范围内零Q，guard缩小setup入口，不能推断被拒路径是否有clean。[终态分解与落子顺序证书](../design/heart-terminal-certificate.md)提议先做一次受限test-only离线诊断，等待新假设接受；不是GoalWindow重跑、策略实现或population许可。#95/#96、reserved/provider/runtime继续停止。

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


## GoalWindow 原型已测量，候选停止

#116 已接受设计；[#94 实施记录](heart-goal-window-prototype.md) 与 [session driver](goal-window-driver.md) 保留测试原型及完整接线说明。#137 后唯一登记的80配对实测已完成，双臂均0 clean，candidate全部68,706次决策为BASELINE_CHOICE；前500块clean与配对clean净收益两个门失败，候选停止，不重跑或调参。详见[实际结果与证据索引](heart-goal-window-result.md)。实现/预检成功不等于候选收益通过；#95/#96、reserved、provider与runtime/default adoption继续停止。
