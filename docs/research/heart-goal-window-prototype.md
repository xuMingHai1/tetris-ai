# GoalWindow 原型实施状态

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)，设计来自已接受的
[#116](https://github.com/xuMingHai1/tetris-ai/pull/116) / [无状态终局窗口](../design/heart-goal-window.md)。

## 已实现的采集前置

`src/test/java/xyz/xuminghai/tetris/ai/GoalWindowCandidate.java` 只存在于测试 classpath，
不进入普通 jar 或 runtime factory。输入只有 20×10 的当前 `GameSnapshot` 与其中已知 preview；
没有 seed、bag、suffix、历史意图、环境开关或跨调用决策状态。

候选完整枚举 current，优先选择 SURVIVAL rank 最小且经生产 replay 确认的 Q。
无 immediate Q 时，对 current top-5 的合格 setup 完整枚举唯一 preview，按两级 rank
字典序选择窗口；rank 1 保留，rank 2–5 受同一次 top-1 的原 risk/budget 约束。
精确 terminal Q 可绕过原 guard，setup 不享有例外。没有窗口则调用原生产 greedy，
不复制 private selector，也不把它称作 executor fallback。异常直接传播。

观测 callback 只输出每次选择、完整枚举调用数、replay 数、各 terminal 的 Q 事实及
原 guard/top-5/risk/budget 判定，以及已检查 setup 的准入和几何事实；它不参与选择。
最多七次完整枚举是调用数量界限，不是工作量或请求 deadline；尚无实测延迟结论。

`GoalWindowCandidateTest` 使用人工 occupancy 与真实生产枚举/动作 replay 检验：

- 原 guard 拒绝的 immediate Q 与稳定 rank 选择；
- rank-1 setup、DANGER 拒绝非 baseline setup、真实下一 snapshot 重算 Q；
- 窗口丢失后重新选择；clean 非 Q 与实际 completion 口径分离；
- REQUIRED/FORBIDDEN、三类额外 holes、高度、真实消行后的 anchor；
- 缺 preview、无 landing、部分播放方向/坐标、重复及变更 snapshot。

这些 fixture 展示新选择干预及规则边界，**不证明从空棋盘可达，不是 population 数据、
存活或计时证据**。测试中的执行观测不替代未来 runner 的 actual-clean/非法计划记账。

## 第二阶段：实际落地证据与配对编排

`ai.benchmark` 同包的测试源码 `GoalWindowPairedRunner` 直接调用原
`HeadlessGameRunner.runPlanningObserved`，fallback 为 null，不复制 game loop。
每局新建 baseline/candidate；factory 不接收 seed、phase、顺序或未来块。完整计时
仍从生产 agent 调用到 terminal validation，保留每次原始 long nanos，并与生产结果
的 decisions、pieces、sum、max、failure/fallback 交叉核对；额外 observer 记账在计时外。

`GoalWindowGameEvidence` 仅由实际已验证 placement 的 post-clear board 取得
`ShapeTarget.HEART.progress`，复用原 `ShapeCompletionTelemetry` 的首次/持续完成 CSV。
逐步保存 actual progress、Q、完整候选观测与成本；测试源码中 Q helper 的 public
可见性仅供同一测试 classpath 的跨包 adapter 复用，没有扩大生产 API。
预测 setup 的 pending 记录不会增加 clean；下一已验证 turn 分别记录实际 Q、clean 非 Q、
未 clean 或 board/type 变化，游戏结束/失败时无下一观察则保留 NO_NEXT_VALIDATED_TURN。
这里的上下文比对只覆盖 board 与当前 type，不声称全 snapshot 等价。

session 先执行两臂各 19000–19009 × 10 决策 warmup，再按 seed 奇偶交替两臂。
warmup 提前死亡也失败，不缩短为成功；首个异常立即中止，不启动后续 arm/seed。
审计 wrapper 记录 delegate 是否抛错、是否返回待验证计划；只有原生产 validator
拒绝已返回计划时计 illegal plan。缺失/重复/跨臂候选观测是 evidence failure，
不会复用上一步观测或伪装成非法计划。异常对象保存已完整记录的前缀、失败分类与
primary/illegal 计数，失败局没有正常 `GameBenchmarkResult`，也不填成 bounded miss。
外部超时、持久化 partial 输出与全轮 INCOMPLETE 将由下一阶段 driver 接线落实。

新增行为验证使用人工 HEART board、调度 stub 和最多三步的生产 loop smoke，覆盖
clean 非 Q、预测与下一实际完成、上下文变化、完整 nanos、真实 validator 拒绝、
agent 抛错、陈旧/重复观测、每局新 agent、warmup/顺序及失败即停。
调度 stub 不执行 80 局；这些测试不提供存活、收益、总体延迟或空棋盘构造结论。

## 尚未交付，禁止采集

本次是 #94 第二阶段，不关闭 #94。仍需在同一问题下完成：

1. 同包 `GoalWindowPairedApplication` CLI，将 runner 的原始证据与 partial failure
   持久化到专属格式；没有此入口时不宣称完整采集链路已就绪。
2. 干净 Wrapper 构建及实际 classpath/class 来源/hash 认证，拒绝旧 target/FQN shadow，
   并通过未修改 frozen source guard。仅 source SHA 不代表二进制来源已认证。
3. 专属 driver/两个 #105 envelope/80局合并 prototype 继续门及其负面数据验证。
4. 既有 reusable workflow 内的显式双臂模式、selector 回归；原 shared-only
   heart-baseline、guard、v1.1 与 #105 分析器保持不变。
5. 最终实现 revision 独立 Review 和适用 CI 成功后，才运行一次已登记开发/校准
   20000–20079 双臂测量；单直接 JVM、45分钟总上限、60分钟 job。

本次没有执行 candidate/baseline population，也没有新增 Actions 采集路径。
没有 actual clean、净收益、存活或延迟门通过的结论。#95 / #96、30000–30199、
31000–31002 与 runtime/default adoption 继续停止。

生产 API、包职责、运行方式和构建不变，因此 README/AGENTS 不需改变生产契约；
测试原型的分阶段交付状态只在本页与任务/PR 记录。未来配对接线再更新运行说明。
