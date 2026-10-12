# HEART 在线构造：设计审查与停止建议

任务：[#93](https://github.com/xuMingHai1/tetris-ai/issues/93)，阶段 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。

状态：**待独立 Review 的设计稿；建议否决下面的候选，暂不进入 #94。** 这不是已接受的阶段决议，也不是“HEART 不可构造”的证明。#93 明确允许交付“目前没有值得原型的候选”；本稿选择该路径，不为推进流程引入未经支持的安全阈值。只有本设计评审被接受后，才在 issue 中记录最终停止结论。

核对基线：`main@565b3da3906357c388951206b58c68f341eb2259`。#104 已合并，#92 协议与基线接受；另有测量路径 selector 修复正在并行处理，本稿不修改其 workflow。采用标准仍为 [HEART v1.1](../research/heart-acceptance-v1.md) / [JSON](../research/heart-acceptance-v1.json)，不改协议或消费预留 seeds。本文仅交付设计，不改变生产 planner、factory 或通用 safety。

## 可核对的输入与阻断层

| 事实 / 源码 | 对设计的约束 |
| --- | --- |
| [GameSnapshot](../../src/main/java/xyz/xuminghai/tetris/ai/GameSnapshot.java) | 只含 settled occupancy、当前 type/cells/rotation 和可选唯一 preview；没有 seed、bag 游标、下一整袋或 eventual outcome。历史已出方块也不是当前契约字段。不能由评估 runner 偷渡未来序列。 |
| [ActionStateSearch](../../src/main/java/xyz/xuminghai/tetris/ai/ActionStateSearch.java) / [ActionPlanCandidates](../../src/main/java/xyz/xuminghai/tetris/ai/ActionPlanCandidates.java) | 路径从实际旋转/坐标重放；碰撞复用 BoardRules，落地结果经过真实消行。ranked 枚举所有合法 landing 后使用既有 heuristic 排序，不是有预算的增量搜索 API。 |
| [BuildShapeActionPlanningAgent](../../src/main/java/xyz/xuminghai/tetris/ai/BuildShapeActionPlanningAgent.java) | 常规候选只在 SURVIVAL top-5 内；DANGER 回到 top-1；creative 必须通过相对同一 top-1 的 budget。preview 实验不改变 runtime factory。 |
| [ObjectiveRiskController](../../src/main/java/xyz/xuminghai/tetris/ai/ObjectiveRiskController.java) / [ObjectiveSafetyBudget](../../src/main/java/xyz/xuminghai/tetris/ai/ObjectiveSafetyBudget.java) | risk 来自 SURVIVAL top-1，而非构造候选。生产 adaptive 不选 RISKY，所有常规档 additional holes 为 0。改变这些语义必须作为独立构造 policy 评审，不能修改通用边界。 |
| [ShapeTarget](../../src/main/java/xyz/xuminghai/tetris/ai/ShapeTarget.java) / [ShapeProgress](../../src/main/java/xyz/xuminghai/tetris/ai/ShapeProgress.java) | 固定 bottom-center occupancy；REQUIRED 32、FORBIDDEN 16、SUPPORT_ALLOWED 16。error = missing required + forbidden occupied；clean 必须 error=0。support 中性不是安全证明；target 外 occupancy 仍影响存活。 |
| [ShapeWitnessConstraintAudit](../../src/main/java/xyz/xuminghai/tetris/ai/ShapeWitnessConstraintAudit.java) | first blocker 顺序为 TOP_FIVE → DANGER_SUPPRESSION → SAFETY_BUDGET → GREEDY_SELECTION。各计数可重叠，不能相加为独立失败数。 |

已完成实验的数值与限制来自 [研究索引](../research/README.md) 和 [详细记录](../research/ai-experiment-history.md)，本轮没有重跑：

- 两个 clean witness 共 22 步：19 步 top-5 外，17 步 DANGER，22 步 raw budget 拒绝；仅排除 FORBIDDEN holes 仍 0/22 通过。第一阻断层不等于唯一阻断层。改 rank cap 不能同时解决 risk、budget 和跨步选择。
- witness 搜索使用固定完整未来序列；同序列的 viability search 到 depth 4 与 greedy 到 24 是离线 continuation 证据，不能给在线 planner 读取 suffix。bounded beam miss 不是死亡证明。
- full reachable 实验扩大选择但没有 clean 改善；一步 preview 20×500 clean 仍 0、horizon 19→18；preview-count guard 三档 10×250 horizon 从 10 降到 3/2/2，clean 仍 0。可落下一块不阻止累计损伤。
- persistence 无可靠分界；first-clearing intervention 的 aggregate +625 仍有最坏 -614 和两个 baseline survivor 损失；feature audit 最好 numeric AUC 约 0.60，未形成可采用 eligibility。不能用 warning 或平均净收益代替安全边界。
- #92 的已接受 80 局基线 0 clean、54 达 1000 块，仅说明当前 greedy 在开发/校准人口不足；不支持从数据拟合新 runtime 阈值，也不支持稳定构造结论。

## 审查的唯一候选：带债务清偿的两块构造事务

候选假设是：把一次中性/暂时退步的 setup 与已知 preview 的清偿动作绑定，要求事务结束取得真实 shape 改善，避免每块独立 greedy 丢失 setup 意图。这与“对每步继续 top-5 的 preview 打分”不同：它需要跨方块的意图与可核对的中间损伤清偿；与 count guard 不同：可达数量不能充当清偿标准。这里只审查此假设，不选择第二个算法或扩展搜索深度。

假想输入仍只有 snapshot。当前方块与 preview 可以确切模拟；其后只允许枚举全部七种 type 作为不确定性压力测试，不给概率、不读取 bag 或 seed，不声称这些分支组成实际连续未来。路径和每步 post-row-clear occupancy 均来自生产模拟器，不保存另一套棋盘或 settled 颜色。

事务需要记录起始 board、setup 的预期 post-clear board、preview type、预期终态与清偿目标。意图不得保存可直接播放的跨方块动作队列：第一块返回当前 snapshot 的 AiPlan；下一块从真实 orientation/cells 重新生成合法路径。清偿目标至少要包含 required/forbidden 变化与全棋盘 health，不允许只记录 netScore 把 FORBIDDEN 增长抵消掉。

假想排序为：完整清偿且 clean 优先，其次 forbidden 不增加且 required 增加，再按生产 SURVIVAL 排序及固定枚举顺序消除 tie。单块 setup 的 error 退步只可作为事务债务，不能永久提高容忍度。无严格终态改善、无 preview 或只靠未知未来才能清偿时，直接放弃事务并选择 SURVIVAL top-1。没有合法 landing 时仍由生产正常 game-over 路径处理，不能构造非法 fallback。

### 独立安全边界的必要条件与缺口

若此候选要越过 top-5/DANGER/raw-budget，需要单独的构造 safety；不得修改 ObjectiveRiskController、ObjectiveSafetyBudget 或 SURVIVAL/TUCK_HUNTER。原预算拒绝原因必须原样记录，不能以候选收益反向调整 baseline。

必要但**不足以接受**的条件为：setup/preview 均有真实合法计划；两块后债务归零；候选终态相对从同一起点执行两次 SURVIVAL top-1 的对照，不增加 raw holes 且不减少 headroom；对两端终态的每种未知 type 都有合法 landing，并报告对应 SURVIVAL 后续 board health。baseline 的已知 preview 不可落下时，不伪造第二步 baseline。无法完成同输入比较则禁止 creative deviation。

这里的主要缺口有两个：

1. “债务”没有获支持的中间上限。两步终态不更差仍可能有危险中间态；将 witness 的 headroom 最低 8 或 warning 的 2/15 转为阈值没有证据。允许 setup 越过生产 guard 就是新的牺牲性堆叠权限，不能凭“下一块会补回来”批准。
2. 两步清偿与七种单块可达都只是有限局部事实。重复事务可以累计坏的几何结构；七种 probe 已存在于 recovery diagnostics，不能把它改名为证明。增加深度或数值权重并没有解决跨事务存活保证，且可能超出成本预算。

在缺口解决前，候选所有越界 setup 均被拒绝，保留生产 guard 后又没有证据表明该候选实质突破已经否决的一步 preview。因此本稿**不冻结一组臆测的中间牺牲阈值，不建议实现此候选**。这是否值得独立补充诊断，应由后续新假设重新评审，而不是让 #94 一边实现一边选安全语义。

### 有限资源与停止语义

为使审查假设有可证伪边界，定义其诊断设计上限：当前根最多 8、每根 preview 最多 8、终态未知压力分支固定 7；最多 64 个两块事务。另计算同起点唯一两步 SURVIVAL 对照，其七种压力 probe 可在所有事务间共用，因此最多 455 个未知 probe（64×7+7）。截断顺序只能是生产 SURVIVAL 排序，不能选 witness rank 或根据已知 seed 调节。此 rank 8 是待否决候选的资源封顶，不是推荐 runtime rank cap。

这些数量**不限制每次 ActionStateSearch 的内部成本**。若要做诊断原型，还必须通过共享生产搜索提供同一请求全局 20,000 visited-state 上限和 20ms 软停止预算，包含枚举/排序/比较；不得复制一个 benchmark 搜索器。现有 ranked/search 没有此入口，本稿不批准改造。20ms/20,000 是假设的最大投入，不来自历史延迟，也不保证可完成一次枚举或满足 50/250ms 完整决策门禁。

预算耗尽、截断导致缺失压力分支或比较不完整时，丢弃所有事务结果，不把部分 frontier 当作 safety 通过或无解证明；返回从最新 snapshot 生成的 SURVIVAL top-1。合作式软停止可能超时，须单独记录枚举、probe、整个 agent 调用和验证成本。算法正常拒绝事务属于 primary 策略内的 SURVIVAL 选择，必须计数；不得把异常/非法计划触发的 executor fallback 隐藏成正常选择以绕过 v1.1 的零 failure/fallback 门禁。

## 生命周期与失败处理

此候选即使被未来设计重提，也只能先 benchmark-only；每局新建 agent，不跨局训练。状态机为 IDLE → SETUP_PROPOSED → AWAIT_PREVIEW → IDLE：

- 仅在观察到真实锁定/消行结果与预期一致后，才进入 AWAIT_PREVIEW。当前 AiPlanningAgent 只有 plan(snapshot)，没有已提交结果回调；下次 snapshot 比对不能区分所有取消/重开情形。接线需要明确 owner/lifecycle，不能让一次未应用的 plan 修改已提交意图。
- 下一次真实 board/type 与预期不符、缺 preview、部分播放受阻重算、方向/坐标改变，立即丢弃剩余意图；从新 snapshot 重算或 SURVIVAL。行消除不是异常，必须匹配已预测的 post-clear board。
- 不“撤销”已落地方块或恢复旧棋盘；撤销只取消意图。重复失败事务不继续堆 setup；本次机会丢弃，下一次只能从真实棋盘建立新事务。
- 暂停、人工输入、关 AI、重开和 stale generation 都应取消意图。GameSnapshot 不携带这些事件；runtime 接入必须另行定义 lifecycle 接线，不能猜测事件或延伸本稿授权。异步应用仍由 AiDecisionExecutor/GameWorld 管理，重力前本地 fallback 契约不得改变。
- 首次真实 clean 后转 SURVIVAL 至死亡/1000 块，继续报告 post-clean survival；v1.1 不要求保持图形，不能提前停局或以预测终态冒充 clean。

## 可证伪预期与原型决策

候选若声称不同于被否决的一步 preview，至少应在纯合成局部 fixture 中展示：生产 greedy/preview 因 setup 中性而错过、事务终态却有真实 required 增加且 forbidden 不增加；且中间损伤有事先明确的界限，路径从实际旋转重放，消行后目标仍成立。再提供反例 fixture：局部清偿/全七种可达但重复事务损伤累计，确认不能把局部 pass 解释成存活保证。fixture 只是行为检验，不是验证人口。

**本轮结论是设计层面的否决建议，不能进入 #94。** 当前既未给出获支持的中间安全界限，也未展示跨事务改善的独立证据；资源上限和提交生命周期还需要生产边界改造。不存在可以明确冻结、且不重复已否决方案的可执行候选。不是“稍后增大 depth/beam 就会解决”，也没有算法实现或性能通过的结论。

Review 若接受停止建议：在 #93/#91 记录否决及本 PR；#94/#95/#96 保持未解锁，#105 的独立 paired 工具可继续，不因本设计否决而停止。若 Reviewer 提出新的可执行假设，则先更新本设计并独立 Review 新 revision；禁止把补充建议直接当成原型许可。

未来重新开启原型时，事前定义继续门槛：开发/校准 80 局至少出现前 500 块真实 clean、paired candidate-only 多于 baseline-only；存活均值/中位数、回归比例、最大损失、survivor loss、horizon rate、完整决策 50/250ms 和零 failure/fallback 逐项按 v1.1 判定，任一失败停止。该较弱构造筛选只能判断是否值得 #95，不能代替独立 200 局的 ≥180 clean、Wilson 下界 ≥85% 和 exact paired 检验；禁止用 80 局宣称采用通过。当前没有运行这些实验或消耗 30000–30199 / 31000–31002。

## 文档与交付边界

仅新增本设计及研究索引入口；未变更系统结构、运行方式、依赖或生产契约，README/AGENTS 无需修改，也不新建 ADR。历史协议保留其提交时的预注册叙述，索引标明 #92 已接受及 #93 的真实待评审状态。新 PR 本轮保持 Draft，后续独立 Review 才决定是否接受设计停止建议。
