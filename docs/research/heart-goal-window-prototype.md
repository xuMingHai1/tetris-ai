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

## 尚未交付，禁止采集

本次 Draft 是 #94 的第一步实施，不关闭 #94。仍需在同一问题下完成：

1. 同包 `GoalWindowPairedApplication`，复用 `runPlanningObserved`，真实完整 nanos、
   per-turn actual clean、failure/非法计划和预测兑现记录；两臂各一次 100-decision warmup。
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
