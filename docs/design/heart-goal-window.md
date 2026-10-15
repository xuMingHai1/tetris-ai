# HEART 新候选评审：无状态终局窗口

任务 [#115](https://github.com/xuMingHai1/tetris-ai/issues/115)，阶段 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。源码事实基于 `main@460182799eb51e0f5ca7cf20c32923e5c4813d5a`。

**本稿仅交付设计评审。** 接受与否以任务及 PR 最终决议为准；独立评审和合并前，#94/#95/#96 全部保持停止。若接受，最多允许 #94 实现下述测试源码中的候选与开发/校准 paired 评估，不批准生产 API、runtime 接入或默认采用。

## 问题、已有证据与新假设

[逐前缀域设计](heart-prefix-domain.md) 证明 clean HEART 至少有 12 个 raw holes。风险控制实际读取同次决策的 SURVIVAL top-1 resulting board；不能把目标的 12-hole 下界说成所有 clean 转移必被拒绝。但旧 witness 的 22 步均未通过原 raw budget，最终完成步骤也没有独立的终局选择语义。

已有结果继续有效：[两块债务事务](heart-online-construction.md) 未获原型许可；一般 preview shape 打分和 continuation-count guard 已否决；[原域诊断](../research/heart-prefix-diagnostic.md) 的 23 份文件经 #114 核验为 20 bounded misses、0 clean，构建来源及 seed 生成仍未认证。它们不证明 HEART 不可构造，也不支持重跑、调宽原域或从历史 extrema 拟合安全阈值。

本轮只审查一个新假设：**保留 ordinary greedy 的前期构造；只在当前块或唯一已知 preview 能通过真实合法路径达到精确终局时，允许一次终局选择例外。** setup 仍受原生产保护；不以小幅 visual-error 改善、未来分支数量或承诺稍后清偿取得权限。终局条件不是安全证明，其构造收益与存活代价必须通过 paired 实验判定。

这不同于旧 preview：旧算法两层均限 top-5 并通过原 risk/budget，再比较一般 shape score；本候选只对精确终局枚举完整终结落点，终局分支不经过该 risk/budget。它也不维持跨块事务。终态几何与旧 D 相同，但约束施加时点不同，是新的干预假设；不是延续 #111 的逐前缀可达性实验或改其参数。

## 固定终局条件 Q

所有 board 均为生产 `PlacementCandidate.resultingBoard()` 的真实 post-row-clear occupancy，固定 20×10、原 HEART 与原 anchor。`Q(B)` 必须同时满足：

| 维度 | 固定条件及来源 |
| --- | --- |
| 视觉 | `ShapeTarget.HEART.progress(B).cleanCompletion()`：32 REQUIRED 命中、16 FORBIDDEN 全空。 |
| 高度 | 生产 occupancy scan 的 `maxColumnHeight <= HEART.height()`，即底部八行内；没有经验 headroom 阈值。 |
| 额外 holes | 生产 `analyzeWithHoleCells` 的 hole coordinates 经原 role 映射，REQUIRED、SUPPORT_ALLOWED、OUTSIDE_TARGET 分别为 0。FORBIDDEN holes 不扣除、不改 raw 定义。 |
| 动作 | 对应当前 `AiPlan` 来自 `ActionPlanCandidates`，并由 `ActionPlanSimulator` 重放确认同一 post-clear board；预测 preview 使用原 `snapshotForSpawnedPiece` 的初始 down 边界。 |

由旧结构推导，Q 的 raw holes 恰为 12；这是几何核对，不是“12 holes 安全”。不拿 shape gain 抵消任一维度，不接纳正 visual error 或只有 REQUIRED 全覆盖的终态。静态 Q fixture 不证明从空棋盘可达。**Q 仅是干预条件，比 v1.1 clean 更窄；真实 clean 即使不满足 Q，也必须计入首次/持续完成，setup 自身已 clean 时也立即计入，不能等预测的 preview。**

## 唯一的选择规则

候选 `GoalWindowCandidate` 每次只接收当前不可变 `GameSnapshot`，没有 seed、bag、generator、suffix、run order、历史 witness 或 eventual outcome 输入。target 固定。候选不保存跨调用状态，也不读取进程环境选择策略。

1. 完整调用一次 `ActionPlanCandidates.ranked(snapshot)`，保持生产 SURVIVAL 顺序。若没有合法候选，使用原 baseline 的返回语义；runner 的可达性预探测仍负责正常 game-over，不伪造终结落点。
2. 若当前候选中存在 Q，选择 SURVIVAL rank 最小的 Q，返回它的当前块计划。仅这个精确终局分支可在隔离 benchmark 内越过 top-5、DANGER 与 raw budget；原拒绝理由仍逐项记录。
3. 没有当前 Q 且 preview 已知时，setup 只取原 current top-5。rank 1 可用，它不需额外 creative 准入，但不代表结果满足 Q 或有安全证明；rank 2–5 必须由同一次 current SURVIVAL top-1 得到非 DANGER risk，并通过该 profile 的原 `ObjectiveSafetyBudget`。中间 FORBIDDEN/各类 holes/高度均记录，不能新增损伤额度或声称中间满足 D。
4. 对每个合格 setup，使用其真实 post-clear board 和已知 preview，经原 spawn helper 完整枚举一次 preview 落点，只保留 Q。按 `(current survival rank, preview survival rank)` 字典序选 pair；返回第一块计划，不返回或保存第二块动作队列。相同 rank 不会对应多个条目，生产枚举顺序决定稳定 tie-break。
5. 缺 preview、所有窗口为空或比较失败时，调用未修改的 `BuildShapeActionPlanningAgent.plan(snapshot)`。这是原 guarded greedy 的 `BASELINE_CHOICE`，不是异常触发的 executor fallback；不复制它的 private selector。非法计划/异常不能伪装为正常选择。

当前 Q 优先于 preview Q，因而在下一块真实 snapshot 与预测 spawn 状态一致时，重算仍先选择某个真实 Q；不保证选择同一条动作路径。重力推进、部分播放、方向变化或 board 不一致时，只从最新 snapshot 重算，可能失去窗口，不能宣布已完成或兑现旧承诺。该条件性质与取消/重开 fixture 分开验证；没有提交意图或 lifecycle 回调。

本候选整个游戏使用同一无状态规则，不设置预测/首次 clean 锁存，也不提前停局。首次真实 clean、clean streak 和后续存活只由 runner 的已验证 placement 观察。协议不要求保持图形；仍运行到死亡或 1000 块。

第一次 `IMMEDIATE_Q/PREVIEW_SETUP` 干预前，候选轨迹与原 greedy 相同。它只截获该轨迹附近的终局窗口，不创造遥远的建设路径，可能整轮没有一次干预；此时按固定投入无收益停止，不能调整 Q、rank 或 seed 继续筛选。

## 资源与失败语义

每次 `agent.plan` 内最多 1 次 current 枚举、5 次 preview 枚举、1 次原 greedy baseline 枚举，共 **7 次完整枚举调用**，不包含 runner 在计时外做的 prepare 可达性预探测；计划重放和 role 扫描单列计数并计入完整成本。没有第三块、未知七种 probe、beam 或离线未来搜索。top-5 只是原 setup envelope，不把它宣称为安全结论。

当前 `ActionStateSearch` 没有取消、visited-state 或请求 deadline 接口。7 次调用不限制单次内部工作，也不保证 50/250ms；本设计不批准修改该 API。为保持策略确定性，不依据墙钟选择不同的部分结果。实现须完整评价上述固定范围；计算错误导致比较不完整时使本轮失败，不能挑 partial winner。

仅为控制离线投入，每 seed/arm 由直接父进程施加 60 秒外部超时，保留原始输出，记 `INCOMPLETE` 并停止整轮；不能当作正常 game-over、bounded miss 或在线预算。沿用 reusable job 的 60 分钟总投入；job 被取消/超时同样不是完整 paired 数据。不得提高限额或缩小枚举范围挽救本候选。

完整决策从 agent 调用到合法 terminal placement 验证结束，包含 baseline 选择、全部枚举/排序、Q、replay，依 [v1.1](../research/heart-acceptance-v1.md) 要求 pooled P95≤50ms、max≤250ms。预探测与 observer/输出仍在计时外；观测到的枚举数/耗时不是 runtime 硬上限。

## 生产冻结与可实施的原型边界

源码实际可见性为：`ActionPlanCandidates`、`ActionStateSearch`、`BoardOccupancyMetrics`、spawn helper 均 package-private；`HeadlessGameRunner.runPlanningObserved` 也只在 `ai.benchmark` 同包可见。不能写成当前已有公共枚举 API。

若设计接受，#94 仅可在以下正常测试目录实现同包原型，不修改 `src/main`、`pom.xml`、`.mvn`、wrapper 或资源：

- `src/test/java/xyz/xuminghai/tetris/ai/GoalWindowCandidate.java` 及行为测试，复用原搜索、risk、budget、role 与 replay，不复制碰撞/旋转/消行/greedy selector。
- `src/test/java/xyz/xuminghai/tetris/ai/benchmark/GoalWindowPairedApplication.java` 及观测 adapter，复用同包的 `runPlanningObserved` 与现有 telemetry；只编排、记录和格式转换，不写第二套 game loop。
- 专属解析/驱动脚本与既有两份 workflow 的明确接线；#105 paired 分析器和原 frozen guard 保持未修改。

原型不是可分发 runtime：正常 jar/factory 不加载 test classes。不得用目录与 package 不一致、reflection、shadow production class、复制引擎或扩大 guard 白名单取得访问权限。将来生产化需另行评审正常源码边界和同策略等价，测试原型接受不能自动授权迁移。

从最终测量 SHA 的干净 worktree 执行 Wrapper `clean test-compile`，生成明确依赖 classpath；在同一 application classloader 的普通 classpath 运行测试入口，使两个同包 helper 正常访问。不得复用旧 `target`、远端不明 classpath 或隐藏的用户缓存 class。两臂使用同一已记录 classpath、JDK/JVM flags；baseline 只实例化未改的生产 `BuildShapeActionPlanningAgent`，rescue=false。

采集前运行未修改的 `verify_heart_production_base.py`，仍与 `2d3f418afdc0454d160cda3599c5c688a8b1f670` 比较，结果必须 true。另保存完整测量 checkout/clean 状态、protocol/config SHA、依赖解析及版本、实际 classpath、main/test class 文件 hash 清单；拒绝 main/test 同 FQN 覆盖，登记实际加载的关键 class 来源并核对本次产物。仅参数 `source_sha` 不是构建证明。若正常编译/同包运行不能通过，停止原型，不临时增加生产 API 或 build 配置例外。

未来 `heart-terminal-paired` 是同一个 reusable workflow 内的显式双臂模式，先做原 source guard 并采集完整 80 局生产 baseline，再与独立 80 局 candidate 按 seed 交替运行；“先做”表示先通过 guard，不把两臂执行顺序改成整批 baseline 在前。baseline/candidate 原始文件和身份严格分开，baseline 保留原 analyzer 可核对的日志及新增 paired facts；不另外跑第三臂。

新候选专属路径选择该模式时，必须同样包含原 frozen guard 与完整 baseline，绝不能改选旧 shape-feasibility 来避开校验。只有 shared workflow/旧 measurement 变更时仍走原 heart-baseline；旧模式和固定参数不变。selector 回归须覆盖两种单独路径、混合变更、same-repository/default-branch 限制与每 revision 一个模式。workflow/selector 的提议需在 #94 的真实 diff 和独立 Review 中验证，本设计本身没有接线或执行新模式。

## 行为证据、样本与继续门

原型首先提供真实生产 replay 的 synthetic fixture：当前 Q 在 top-5 外或被原 guard 拒绝但合法完成；无当前 Q、受原 guard 允许的 setup 后，下一真实 snapshot 重算仍能 Q；setup 越界拒绝；完整 REQUIRED 但有 FORBIDDEN 不算 Q；额外 hole/超高终态拒绝；实际 clean 但非 Q 仍计完成；真实消行后重新锚定；非初始旋转、缺 preview、无 landing 与重复/变更 snapshot。同一 snapshot 改变 runner seed/suffix/run order 不得改变候选 plan；接口和依赖检查同时保证这些量未传入候选。fixture 必须展示规则和新干预，不把人工棋盘说成空棋盘可达或 measured population。

计数测试验证最多 7 次枚举、终态与 setup 的不同权限和稳定 tie-break；负面接线测试拒绝旧 target、class shadow、错误 source/protocol/config、缺 seed/顺序/计时、预测 clean 冒充实际 clean。wrapper 干净构建、现有质量与 frozen baseline gate 均须通过；新 test-only planner 不降低 95% core PIT 或把其未纳入生产 PIT 说成候选已被 mutation 验证。

通过上述实现 Review 后才采集开发 20000–20019、校准 20020–20079，两臂各 80×1000，seed 奇偶交替、同环境、各自 19000–19009 的 100 次 warmup 决策、每局新建策略。开发/校准已被旧研究使用，不称为 fresh validation。固定一个候选和参数，不扫多个 terminal/根数版本后选优。

输出复用 [#105 接口](../research/heart-paired-interface.md) 的两个 population envelope，包含真实 per-decision nanos、post-clear facts、illegal plan、failure/fallback、执行顺序与 source/config 证据；额外记录 `IMMEDIATE_Q/PREVIEW_SETUP/BASELINE_CHOICE`、每维 Q、原始 guard 拒绝原因、枚举/replay 数和预测窗口是否在下一步实际完成。预测 Q 不能写入 actual clean。非法计划由生产验证实际判定，不能默认填 0；错误/超时保存 partial 证据但整轮失败。

进入 #95 的必要条件为前 500 块出现真实 clean，candidate-only 多于 baseline-only，且 v1.1 的存活均值/中位数、≤5% 回归 seed、最坏损失≤100 块、0 baseline-survivor 损失、horizon rate、50/250ms 和零 failure/非法计划/fallback 逐项通过。开发/校准 analyzer 的 exit 0 不代表这些门已通过，需实际读取每个 gate；任一失败停止，不换 seed 或调终局约束救活。

即使通过，#95 的 30000–30199、≥180/200 clean、Wilson 下界≥85%、exact paired 检验以及 #96 的 Windows 31000–31002 门禁仍须独立解锁。合成成功或仅获得一个真实 clean 都不能宣称稳定 HEART、可在线采用或默认提升。

## 本轮评审交付

本 PR 仅本设计与研究入口，没有原型、工作流变更或 population 采集。评审要判断新干预是否可证伪、终态权限是否限定清楚、测试源码/二进制来源路线是否成立、资源/输入/实际结果是否诚实，以及可否仅允许 #94 的隔离原型。若否决，记录理由并继续停止全部依赖任务；若接受，#94 也只解锁上述受限范围，#95/#96 不解锁。

README/AGENTS 当前生产结构与运行方式未改变，历史协议/否决记录保留；实际接线时再同步其适用的 benchmark 运行说明。本设计不创建新的通用搜索/Agent 框架或 ADR。
