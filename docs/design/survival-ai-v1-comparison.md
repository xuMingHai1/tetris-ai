# 本地 AI 生存增强 V1：唯一候选配对设计

状态：设计提案，尚未完成独立设计 Review / CI / 正常合并；未执行正式对照，未测得收益。
2026-11-17 用户选择本路线。目标是先验证既有本地 lookahead，只有通过才交付显式可选的桌面增强模式。

## 起点与有限历史核对

核对时 GitHub default 为 `main@3f114cd5236dc695e07a916201344b6e9bb33da7`，本地一致。
本提案只改文档，不改策略、玩法、测量实现、原冻结协议或 guard。

[PR #21](https://github.com/xuMingHai1/tetris-ai/pull/21) 已合并，head
`4382e5c350a6b00d935e4e8ad0e4181c2f33e5d7`，merge
`8543dc09b55ca5b60b78c967db04d55adc1601a2`。
2026-11-17 定向读取其描述和讨论：描述中的 seed 1019/1010/1011 收益属于 Jev + next-piece outlook，
不能归因于纯本地 lookahead；其合并后 20×5000、seed 1000 的纯本地实验是计划，讨论列表为空。
此次有限核对没有取得可复用的原始配对产物；不据此声称历史上没有运行过，也不继续历史全扫描。
旧规则版本和 5000 块上限本身也不满足本轮相同源码、500 块的验收要求。
[PR #41](https://github.com/xuMingHai1/tetris-ai/pull/41) 是另一 BUILD_SHAPE 候选，不是本轮 SURVIVAL lookahead。

## 候选语义与推荐理由

- 对照：当前 `HeuristicTetrisAgent`，placement-oriented SURVIVAL top-1。
- 候选：当前 `NextPieceHeuristicTetrisAgent`，无新增权重、排序、深度或参数。
  当前落点限于同一 heuristic top-5；对每个落点用 `NextPieceOutlook` 模拟已知 preview，
  将各自的最佳 preview response 按原 heuristic 排名，返回对应的当前动作。
- 该比较包含 preview response 的棋盘指标和该步消行奖励，没有直接累计当前步消行奖励。
  这是现有策略语义，不是本次认定或修复的 bug；相同排名保留现有顺序。
- 无当前候选时返回 `AiMove.NONE`；无 preview 或所有 shortlisted preview 均不可落时返回当前 top-1。
  这些内部算法分支不等于 runner 的异常 fallback 计数。
- `HeadlessGameRunner` 复用 seeded 7-bag、生产 `BoardSimulator` / `PlacementCandidate`，
  每个新块在 snapshot 前执行一次初始 gravity。两臂都不配置异常 fallback agent。

推荐先检验已有候选，避免把算法重写与接入混成一次提升。
直接做桌面切换无法证明收益；调整累计消行评分会成为第二候选；追加更长样本会扩大本轮预算。
本设计均不选择这些路径。

## 冻结内容、顺序与预算

本文件在任何正式结果可见前接受独立设计 Review、适用 CI 并正常合并。
随后仅实现下述最小配对计算与功能回归；实现最终完整 revision 也须独立 Review、适用 CI、正常合并。
不得把本提案状态、自动代码审查请求或绿灯当成已经完成独立 Review。

正式执行前在 checkout 外保存一次执行记录：本协议文件 SHA-256、已接受的设计/实现 PR 与
最终 Review SHA、两者适用 CI run/attempt、当前 default 完整 SHA、tree、干净状态、
源码文件身份、构建输入身份、JDK 完整版本与可执行路径、运行 OS、实际 JVM 命令和构建日志。
身份至少覆盖 `core`、`ai`（包括 benchmark）、模块声明、`pom.xml`、Wrapper 及实际使用的依赖版本。
两臂使用同一次干净构建的相同输出，正式执行前登记该输出/依赖文件的 SHA-256。
最终执行 SHA 现在尚不存在，不能以本节起点 SHA 冒充；登记后禁止换构建或继续修改代码。

固定数据与执行方式：

| 项目 | 冻结值 |
| --- | --- |
| 种子 | 既有公开开发 seed 1000–1019，升序，每 seed 每臂一次 |
| 两臂 | 先完整 heuristic，再完整 lookahead，均为 SURVIVAL |
| 每臂 | 20 局，每局最多放置 500 块；合计最多 40 局、20,000 块 |
| 正式采集 | 唯一一次；开始第一臂即消耗本次机会；无 warmup 或预跑这些局 |
| 资源 | 从启动第一臂到第二臂退出总墙钟不超过 1800 秒；每个 JVM `-Xmx1g`，顺序执行 |
| 构建/功能测试 | 单独计时，不运行完整正式样本或提前查看其结果 |
| 环境 | 同一机器、JDK 27、构建/依赖、参数与输入；仅 agent 不同；无网络/provider |

复用 `BenchmarkApplication`，两次调用分别设置：

```text
TETRIS_BENCHMARK_AGENT=heuristic  # 第二臂改为 lookahead
TETRIS_BENCHMARK_GAMES=20
TETRIS_BENCHMARK_MAX_PIECES=500
TETRIS_BENCHMARK_SEED=1000
```

具体 Java module-path / 启动命令由已审查实现的执行记录固定；必须直接对同一构建输出调用
`xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication`，保留 stdout/stderr 和退出码。
使用现有进程 timeout，第二臂只能使用 1800 秒减去第一臂已用墙钟的剩余时间；
timeout 必须终止实际 Java 进程，不能只终止 Maven 父进程后留下子 JVM。
不增加通用监督器。构建在采集计时前完成。
不使用现有 `compare`（它会调用 Jev），不修改共享 workflow 或触发原 HEART/GoalWindow 采集。

## 数据完整性与精确通过门

保留原始日志及现有 CSV/汇总格式；配对计算只消费两臂各自的现有 game CSV。
忽略原日志中的 Maven 输出和 `#` 汇总不能成为忽略坏 CSV 的理由；先按现有采集规则提取 game CSV，
再严格验证恰好两个 arm 各 20 条、seed 集合和顺序完全对应、无重复/缺失/额外 arm、
`piece_limit=500`、所有必需列和有限非负数值、退出码为零、来源和总墙钟预算符合冻结记录。
还需检查 `0 <= pieces_placed <= 500`、`decisions == pieces_placed`、
`lines_cleared <= 4 * pieces_placed`、`reached_piece_limit == (pieces_placed == 500)`，
两臂 `primary_failures == 0` 且 `fallback_decisions == 0`。
异常退出不能用缺少结果行或补零隐去。发现非零失败/fallback 时逐项列出，结论为 INVALID。

令每 seed 对照/候选存活块数为 `Bp/Cp`、消行数为 `Bl/Cl`，总和前缀为 `sum`。
“存活块数”就是成功放置数，不是尝试决策数或墙钟存活时长。全部满足才为 PASS：

1. 两总和均不退步：`sum(Cp) >= sum(Bp)` 且 `sum(Cl) >= sum(Bl)`。
2. 至少一项提升 10%：`10 * sum(Cp) >= 11 * sum(Bp)` 或
   `10 * sum(Cl) >= 11 * sum(Bl)`；所选基线总和必须大于零，零基数不能声称相对提升。
3. 每 seed 按 `(pieces_placed, lines_cleared)` 字典序比较；改善局数严格大于退步局数，平局单列。
4. `Bp == 500` 的任一 seed 均要求 `Cp == 500`。
5. 每 seed 块数下降不超过 10%：`10 * Cp >= 9 * Bp`。消行数的逐 seed 变化同时完整披露。
6. 不能是两臂全部 20 局都达到 500 块；这种双臂饱和直接为 INCONCLUSIVE，
   即使消行门通过，也不据此交付“生存增强”。部分截断局逐项标记。

整数交叉乘用足够宽的整数计算，不先四舍五入百分比。
报告包含逐 seed 原值/差值、改善/退步/平局、均值/总和/相对变化、截断数、每项门的布尔结果，
并披露现有 avg/max decision latency 和 board-health 指标。延迟/棋盘指标只描述，不临时加入或替换收益门。
算法内部无 preview/preview 全不可落的 top-1 回退没有现成逐项 telemetry，本轮不声称统计到了这些分支；
功能测试覆盖其语义，不新增运行时观测或将其混入 runner fallback。

判定优先级：缺失/异常/来源或预算无效为 INVALID（超时另标 TIMEOUT）；
数据完整且双臂全饱和为 INCONCLUSIVE；其他任一收益或回归门未过为 FAIL；只有全部门通过才为 PASS。
任何非 PASS 都保留已有原始产物并停止本候选。不换 seed、延长、调参、改顺序重跑，也不自动换候选。

## 最小实现与验证范围

本设计接受后，下一实现 PR 仅需一个 Java 离线配对计算入口，消费现有 CSV，
不启动游戏、访问 provider 或复制规则；复用现有产物，输出逐 seed 与门结果。
用人工构造数据验证精确 10% 边界、单局回归、500 截断保护、双臂饱和、零基数、平局和坏输入。
功能回归补充 lookahead 无候选/已知 preview 不可落等必要语义，复用生产事实；
不得为了测试跑完整正式 20×500 样本。
README / AGENTS / 架构不因本设计改变运行时职责而修改；研究索引增加独立导航，避免误接旧 HEART 研究。

只有 PASS 才进入显式可选桌面模式实现，复用 `AiDecisionExecutor` 的 generation、snapshot 与重力 fallback。
不改默认策略和已验收教练。桌面阶段另验证切换、人工抢占、暂停/关闭、deadline/fallback、教练回归，
经最终 Review/CI 后交付 Windows 包供用户验收。headless 开发样本通过不证明普遍收益或 Windows 实时保证。

原 #140 / GoalWindow / #95 / #96、reserved、新 population、provider、冻结协议/guard、默认替换、通用 safety
均不在本设计内；保留旧研究 PR #264，不续建 Linux 研究监督器。
