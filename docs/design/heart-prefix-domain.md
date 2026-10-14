# HEART 重新设计：目标留白与每步几何域

任务：[#109](https://github.com/xuMingHai1/tetris-ai/issues/109)，阶段 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。

状态：**待独立评审；仅建议允许下一项 benchmark-only 域可达性诊断，不批准在线 planner 或解除 #94。** 核对源码为 `main@7a6be4efc830dc0f66a64e0a7e603d2d44574961`。#93 已通过 #106 接受否决两块债务事务的结论，#105 已通过 #108 交付分析工具；[旧设计](heart-online-construction.md) 保留评审前叙述，接受状态以 issue/PR 决议为准。

## 新问题与可核对的结构事实

现有 [ShapeTarget](../../src/main/java/xyz/xuminghai/tetris/ai/ShapeTarget.java) 的完整 HEART 必须保留部分列内负空间，而 [BoardOccupancyMetrics](../../src/main/java/xyz/xuminghai/tetris/ai/BoardOccupancyMetrics.java) 将同列最高占用格以下的所有空格计为 hole。这里先证明目标与原始指标的冲突，不把指标改名或把目标留白宣称为安全。

对每个 target-local 列，找到最高 REQUIRED；该格以下的 FORBIDDEN 在所有 clean 棋盘上都为空，且上方 REQUIRED 已占用，因此必定是原始 hole：

| Target-local 列（0 起算） | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 合计 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 必然的 FORBIDDEN holes | 3 | 2 | 1 | 0 | 0 | 1 | 2 | 3 | 12 |

额外占用不能填这些空格而仍保持 clean；额外空格或更高占用只能增加 holes。故 **任意 clean HEART 的 raw holes ≥12**。将 REQUIRED 与两行 SUPPORT_ALLOWED 全部填满、target 外全空的静态棋盘取得等号：32 required、16 support、12 holes、最大列高 8、headroom 12、aggregate height 60、bumpiness 18。它没有满行，已满足 post-row-clear occupancy 的形式条件；静态构造不证明从空棋盘通过某个方块序列可达。

[ShapeTargetTest](../../src/test/java/xyz/xuminghai/tetris/ai/ShapeTargetTest.java) 用生产 role 映射、hole scan 和 controller 验证该等号 fixture。再移除一个 support，visual clean 仍成立，但新增一个 SUPPORT_ALLOWED hole；support 对视觉中性不等于无额外损伤。

在 20×10 上，[ObjectiveRiskController](../../src/main/java/xyz/xuminghai/tetris/ai/ObjectiveRiskController.java) 的 danger holes 是 `max(4, cols / 2)=5`。将上述 fixture 作为它的输入 placement，会因 12 holes 得到 DANGER，尽管 headroom 为 12。**真实 planner 的 risk 输入是同一次决策的 SURVIVAL top-1 resulting board，并非当前 board 或创意 candidate。** 此 fixture 不能证明所有 clean 转移都会被 runtime 拒绝，不能证明 HEART 在现有 policy 下不可能，更不能证明 FORBIDDEN holes 没有存活代价。

## 唯一新假设：不借跨步债务的每步几何域

先问一个较小的问题：是否存在从空棋盘开始、每个实际 post-row-clear 前缀都没有额外 hole/视觉侵入、且保持在 target 所需高度内的 clean 构造路径？这是诊断假设，不是新在线算法。

固定域 `D(B)` 用同一生产 board、target role 和 exact hole coordinates 判定，四项必须同时成立：

1. 固定 20×10 棋盘和原 HEART；FORBIDDEN occupied 为 0。
2. REQUIRED、SUPPORT_ALLOWED、OUTSIDE_TARGET 三类 hole 的数量分别为 0；不以总和净收益抵消一类损伤。
3. 所有 occupied 位于 target 高度对应的底部八行，即 `maxColumnHeight <= ShapeTarget.HEART.height()`、headroom ≥12。target 外两侧列可作为有限 buffer，但也必须满足高度与零额外 hole。
4. 每个转移的动作和消行由生产 ActionStateSearch / ActionPlanCandidates / BoardRules 生成；在真实 post-clear 坐标重新计算上述三项，不能移动 target anchor 或维护虚拟棋盘。

这个域不设置允许暂时增加多少 support hole 的经验阈值，不承诺“下一块清偿”。其高度来自固定 target 几何，零额外 hole 是保守的实验限制；两者都不是已证实的存活边界。在该域内 raw holes 仍真实保留并报告，最多 12 个：第一行的四个 FORBIDDEN 不可能有域内上方占用，其余十二个才可能形成 hole。不得在全棋盘通用指标、SURVIVAL/TUCK_HUNTER 或既有 controller 中扣掉这些 holes。

这不同于已否决的“仅排除 FORBIDDEN 后重分类”：旧 witness 还包含大量 support/required/outside holes，而本域逐个前缀拒绝它们。旧 witness 的 0/22 adjusted eligibility **不支持**本域可达；新限制很可能过严，必须允许诊断否决它。完整枚举只服务于诊断路径存在性，不是放宽 runtime top-5 的授权。

## 第一门：仅做受限离线可达性诊断

本 PR 不实现搜索器。设计接受后，新诊断任务才可在 [现有 feasibility 搜索](../../src/main/java/xyz/xuminghai/tetris/ai/ShapeConstructionFeasibilityBenchmark.java) 的生产候选后增加域过滤与诊断计数；不修改运行时 factory、生产规则、top-5、Risk Controller 或 Safety Budget。复用既有搜索和 witness replay，不另写碰撞/旋转/消行。

事前固定的诊断投入为开发 seeds **20000–20019**、depth **24**、beam **128**；这是既有离线搜索规模，不增加 lookahead 深度。每个序列最多展开 `1 + 23×128 = 2945` 个 board roots，20 个序列最多 58,900 roots。每 root 内的现有 ActionStateSearch 枚举成本不受此数量约束；独立子进程每 seed 60 秒的外部超时仅控制诊断投入，超时记 INCOMPLETE 并停止本轮，不能冒充 miss 或在线 deadline。原始完整序列只属于离线 evaluator，与未来在线策略输入隔离。

必须输出协议/源码 SHA、实际方块序列、完整 witness AiPlan、每个前缀四类 holes/高度/shape progress、生成与域内 placements、每深度 unique/pruned frontier、资源/耗时和终止原因；零值与不可得分开。每个 witness 用生产 simulator 从同一 spawn-gravity 边界重放，重新验证所有前缀和真实 clean。

至少一个重放成功且每个前缀在域内的 clean witness，才值得重新设计在线候选。bounded miss、beam pruning 或超时均不证明不可构造；在固定投入内无 witness 时停止此方向，不临时允许 support 债务、提高高度、扩大 beam/depth 或调 seed 来挽救结果。任何限制变化都是新的假设，先独立评审。

这一步只交付“受限路径存在”的必要证据，**不提供存活、在线可用或 #94 原型许可**。旧 seed 1001/1008 的已知 witness 不能替代本门；若用于回归只能标为历史证据，不能按新 population 计数。

## 第二门：重新评审在线选择，随后才考虑 #94

离线 witness 通过之后，另行冻结在线候选设计并独立评审；本稿不选一种尚无证据的排序、学习器或跨步计划。

- 输入仅为当前 GameSnapshot 与唯一已知 preview；不得读取 seed、bag、完整 suffix、离线 witness 下一步或 baseline eventual outcome。不能把存在性搜索直接装进 runtime。
- 每次从真实 snapshot 的 board 判断是否在 D；只在进入域后尝试域内动作。每次只返回当前方块的 AiPlan，不保存未提交的下一块承诺。重复调用/取消/部分播放不能先提交意图。
- 无域内合法动作、当前 board 在域外或评估不完整时选择生产 SURVIVAL top-1；无合法 landing 仍按生产 game-over 处理。SURVIVAL 可以离开 D，所以 D **不是控制不变集**，不能推导长期安全；下次只能按真实 board 重新判断是否可进入域。
- 没有已冻结的在线选取办法及可控的完整决策预算，就继续停止 #94。现有枚举 API 不能把 root/beam cap 当作请求内硬上限；未来若需要共享搜索预算入口，作为候选设计中的明确生产边界变更评审，不复制搜索器。
- snapshot/orientation/generation、人工优先、重力 fallback、锁定与消行仍由 AiDecisionExecutor/GameWorld 负责；潜在 runtime 接入须另行审查，不能以无跨步承诺省略这些边界。

在线设计接受后，#94 才能实现 benchmark-only 候选与真实 paired runner，复用 [#105 paired 接口](../research/heart-paired-interface.md)。开发/校准 80 局使用 20000–20079，必须出现前 500 块真实 clean 且 candidate-only 多于 baseline-only；v1.1 的存活均值/中位数、回归比例、最大损失、survivor/horizon 损失、完整决策 50/250ms、零 failure/非法计划/fallback 各自判定，任一失败停止。正常 primary 选择 SURVIVAL 单独计数，不能把异常 fallback 隐藏进去。

通过该原型筛选仍不等于采用：#95 的独立 200 局与 #96 的 Windows 门禁继续按 [HEART v1.1](../research/heart-acceptance-v1.md) / [JSON](../research/heart-acceptance-v1.json)。本稿不消费 30000–30199 或 31000–31002，不改完成定义、默认策略或 provider 使用。

## 本轮交付与接受范围

本轮仅新增本设计、两个结构性回归测试并同步研究索引。没有实现域 predicate/search/planner，没有运行诊断 population，没有证明域可达或取得构造提升。静态 fixture 验证与未来真实 paired/桌面证据分开记录。

独立 Review 应判断：12-hole 推导及 production fixture 是否正确；D 的边界与反例是否明确；诊断是否可证伪且资源/未知语义完整；是否保留 #106 否决和 v1.1。若接受，只允许另行建立上述离线诊断任务；#94/#95/#96 仍保持停止。Review 的 no-blocker 不等于设计已合并接受，更不等于 stable HEART。
