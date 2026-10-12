# 稳定 HEART 验收协议 v1.1

任务：[#91](https://github.com/xuMingHai1/tetris-ai/issues/91) → [#92](https://github.com/xuMingHai1/tetris-ai/issues/92)。

本文件与 [机器可读数值](heart-acceptance-v1.json) 是本轮预注册的评审交付。数值先固定并提交，再测 baseline；采集不能反过来调整目标。#92 接受、PR 合并前不启动 #93/#94 或独立 validation。若评审改变定义、seed 或门槛，创建新协议版本并重采相应基线，不用旧版本数据冒充新协议结果。本轮不声称稳定 HEART 已实现。

## 冻结输入与完成定义

生产策略基线为 `main@2d3f418afdc0454d160cda3599c5c688a8b1f670` 的 action-native greedy BUILD_SHAPE：`BuildShapeActionPlanningAgent(HEART)`、top-5、生产 Risk Controller 与 Safety Budget。preview rescue **关闭**，不跑 STRICT/PREVIEW_ONLY 或 Jev；之后若需要 rescue 对照，单独命名为 secondary arm，不能替换主 baseline。

- 棋盘 20×10，随机源 `Xoroshiro128PlusPlus` 的 seeded 7-bag；空棋盘起步，固定 HEART occupancy。REQUIRED/FORBIDDEN/SUPPORT_ALLOWED 复用 `ShapeTarget.HEART`，不比较颜色。
- 当前方块先执行生产的第一次 spawn gravity，然后给策略不可变 `GameSnapshot`，包括当前旋转坐标和唯一已知 preview。评估 seed、完整未来序列和最终结果不能进入策略输入。
- 每次合法计划沿 `ActionPlanSimulator` / `PlacementCandidate` 的生产 post-row-clear 棋盘判定 clean：REQUIRED 全命中且 FORBIDDEN 全空，SUPPORT_ALLOWED 中性。单纯全覆盖不算 clean。
- **本阶段验收首次构造，不要求保持图案。** 记录首次 clean 的 piece、headless wall time、clean turns、最大连续 clean turns、结束时是否 clean，以及首次 clean 后实际放置的块数。持续保持形状是后续能力，不能从首次完成推导。
- 每局运行到死亡或 **1000 块**。目标首次 clean 必须在 **500 块以内**；第 501 块以后 clean 单独报告，不计采用完成率。完成后继续跑满同一 horizon，不能提前停止候选。
- 未完成记 `first_clean_piece=-1` / 时间 `-1`；完成者的时间分布仅代表完成子样本，必须同时报告其分母和未完成数量。headless time 不是桌面游玩时间。

## Seed 与证据门禁

以下区间在提交协议前已与研究索引/历史记录核对，不复用已公布的 1000–12119 实验 population；外部存在未登记样本时应暂停并登记污染。

| 用途 | Seed | 局数 | 每局上限 | 使用规则 |
| --- | --- | ---: | ---: | --- |
| JIT warmup | 19000–19009 | 10 | 10 | 独立 world，恰好 100 次决策；不入结果。warmup 未完成则测量失败。 |
| 开发 | 20000–20019 | 20 | 1000 | 可看逐局/逐步数据、开发和定位。 |
| 校准 | 20020–20079 | 60 | 1000 | 可在 #93/#94 定义候选与预算；不作最终采用结论。 |
| 独立 validation | 30000–30199 | 200 | 1000 | 本轮不运行。候选策略、输入/安全语义、资源上限冻结后，#95 一次运行 paired baseline/candidate。 |
| Windows 运行验收 | 31000–31002 | 3×2 | 每次 120 秒 | #96，起始等级 0 / 10 各 3 个 seed。不是 headless validation 的替代。 |

paired run 使用同 seed / 同输入边界 / 同 horizon，策略每局重新创建，不能跨局保留学习状态。验证时按 seed 奇偶交替 baseline/candidate 的运行顺序；两臂各自相同 warmup。计时环境和非计时结果分别保存。若独立 validation 失败后修改策略，原 validation 已变成开发数据，须预注册新版本、新 population；不得继续重用 30000–30199 调参。

## 候选采用条件：全部满足

以下是**新候选需达到的目标**，不是要求当前 baseline 达标。#92 的采集门禁是输入与数据完整，不把基线未达构造目标当作 CI 失败。

| 维度 | 冻结门槛与计算口径 |
| --- | --- |
| 构造 | 200 局中至少 180 局在前 500 块 clean，即经验成功率 ≥90%；两侧 95% Wilson 下界 ≥85%。不能称为“总体成功率已保证 90%”。 |
| 相对收益 | 对每个 seed 的前 500 块 clean 布尔值作 paired 比较；candidate-only 成功多于 baseline-only，单侧 exact McNemar/binomial `p≤0.05`。只有一个预注册候选，禁止多次选优后沿用此阈值。 |
| 存活均值/中位数 | 逐 seed `candidate pieces - baseline pieces` 的均值和中位数均 ≥0；每臂报告死亡/horizon 数、pieces 的 min/median/P95、每个 seed 的 wins/losses/ties。 |
| 尾部代价 | 回归 seed ≤10/200；最大逐 seed 损失 ≤100 块；baseline 达到 1000 块但 candidate 未到的 seed 必须为 0；候选 horizon rate 不低于 baseline 超过 2 个百分点。所有条件独立判定，净收益不能抵消尾部失败。 |
| 完成后存活 | 不提前停止；单独报告 post-clean pieces 分布、clean 且达 horizon 数，以及完成后死亡 seed。前 500 块 clean 且达 1000 块者至少继续放置 500 块。 |
| 完整决策成本 | warmup 后全部实际决策 pooled nearest-rank P95 ≤50 ms，max ≤250 ms，同时报告每局 max 和最慢 seed。范围为 `agent.plan` 调用至合法 terminal placement 验证完成，含候选枚举/排序/规划和验证，不含 runner 的预先可达性探测、输出/observer。搜索内部耗时只能作分项。 |
| 正确性 | primary failure、非法计划、fallback decision 均为 0；snapshot 只能携带已知 preview；动作和棋盘复用生产规则。 |

200 局允许明确区分经验完成率与统计不确定性：180/200 的两侧 95% Wilson 下界约 85.06%。0/200 的总体 baseline-survivor-loss 事件，其单侧 95% exact binomial 上界约 1.49%；这是整个人口上“损失事件”的界，不是“给定 baseline survivor”的条件概率，也不证明部署零风险。公式参考 [NIST confidence intervals](https://itl.nist.gov/div898/handbook/prc/section2/prc241.htm)。存活差异仍执行上述逐 seed 约束。

prototype 值得继续的判断留在 #93 的被评审设计中，但不能改变这里的最终采用标准。均值 error 变好、clean witness 存在、headless latency 达标或 warning classifier 命中均不能单独触发 runtime adoption。

v1.1 修正记录：在读取第一轮基线指标前，自审发现 v1 的桌面门禁只检查播放/耗时，缺少真实构造结果。v1.1 增加下面的 clean 与完成后存活条件；headless 门槛、population、warmup 不变。第一轮 v1 数据不作为 v1.1 的交付基线，按新协议 checksum 重采。此修改不来自观测结果，也不放宽任何门槛。

## Windows 桌面门禁（#96）

每个起始等级 0/10、每个 seed 31000–31002 各采集 120 秒；保留自动升级与自然重力，记录每次请求时的实际等级、重力剩余时间、generation、ready/application 和 fallback。禁止固定 timeline 或跳过正常播放来提高完成率。早结束的对局记录死亡和实际持续时间，不重开拼接样本。采用结论只覆盖实际采集到的等级区间，不推导等级 30 的实时保证。

- 六次采集均须在实际生产 settled post-row-clear 棋盘上出现 clean HEART，首次 clean ≤500 个真实锁定方块且 ≤90 秒，随后至少继续存活 30 秒；不能用 planner 的预测落点、截图外观或 headless 结果替代。早结束/未完成都记失败。记录实际首次 clean 的 piece/时间以及 clean 后存活时间；六次皆通过仅作运行接入验收，不宣称 Windows population 的成功率已证明 ≥90%。
- request → FX plan-ready 的 pooled P95 ≤75 ms、max ≤250 ms；实际重力期限到达后只能按生产 fallback 契约处理，旧 snapshot/generation 的结果应用数为 0。
- `gravity-fallback / accepted decisions ≤1%`；decision failure、fallback failure、暂停/人工接管后仍执行旧动作数为 0。
- 完成播放比例 ≥99%，分母为有终态的计划，分子为 `completed + gravity-landed`；末尾未结束计划单独列出。受阻重算、人工优先、暂停/恢复/接管必须实际验证。
- 保存 Windows 版本、CPU/RAM、电源模式、JDK/JavaFX、DPI、实际每局等级和采集原始日志。起始等级 10 后自动升级可能缩短 deadline，超过预算则按门禁失败，不能删去不利等级区间。
- 当前 Desktop AI Timing 只给 Linux 观察值，且还没有足够的真实 piece/clean 事件和 per-request level/deadline/generation 日志；#96 须补齐这些可观测字段并做真实 Windows 操作验收。#92 不以既有 120 秒样本冒充此门禁。

## 运行与数据核对

主 baseline 的开发+校准共 80 局，用当前 `BenchmarkApplication`。Linux 示例：

```bash
TETRIS_BENCHMARK_AGENT=build-shape \
TETRIS_BENCHMARK_GAMES=80 TETRIS_BENCHMARK_MAX_PIECES=1000 \
TETRIS_BENCHMARK_SEED=20000 \
TETRIS_BENCHMARK_WARMUP_GAMES=10 TETRIS_BENCHMARK_WARMUP_PIECES=10 \
TETRIS_BENCHMARK_WARMUP_SEED=19000 \
./mvnw --batch-mode --no-transfer-progress \
  -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication \
  compile javafx:run > heart-baseline.log
python3 scripts/analyze_heart_baseline.py heart-baseline.log \
  --output target/benchmark/heart-baseline-summary.json
```

Windows 使用相同环境变量和 `mvnw.cmd`。JDK/JavaFX 均为 27，不修改 release 来适应本机 JDK。

reusable/manual `AI Benchmark` 的 `agent=heart-baseline, games=80, max_pieces=1000, seed=20000` 固定执行此协议；PR selector 遇到本协议/测量/解析器变更自动选择一次该路径。命令与采集仅维护在 reusable workflow，不另建一套 benchmark workflow。

分析器严格核对 80 个 seed、horizon、逐局字段、每个 decision 的连续 timing、首次 clean/clean 诊断一致性和 warmup。分开发/校准报告 clean by 500、any clean、best error、zero-forbidden coverage、full-required intrusion、存活、时间和 pooled P95/max。`min_forbidden_at_full_required=-1` 表示从未全覆盖，不当作零侵入。生成 CSV/JSON 留在 Actions artifact，不提交源码；PR/#92 保存耐久摘要、协议 SHA256、测量源码 SHA、run/artifact 地址和采集环境。artifact 30 天到期后按冻结源码重跑。

接受 #92 表示协议与基线证据可用于设计，不表示新策略已通过门槛。#91 保持 open，只有 #93–#96 的条件门禁及采用结论完成，才讨论关闭阶段。
