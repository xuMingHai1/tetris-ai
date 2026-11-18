# 生存增强 V1 离线配对计算

本实现落实 [PR #267 接受的设计](survival-ai-v1-comparison.md) 的 CSV 校验及收益计算，
不启动游戏、不调用 provider、不改变策略或正式预算。正式采集仍需本实现最终 revision 的独立 Review、
适用 CI、正常合并，以及采集前的完整冻结记录。人工测试数据不提供收益证据。

入口为 `xyz.xuminghai.tetris.ai.benchmark.SurvivalComparisonApplication`，
直接使用最终构建的 module-path，以 `heuristic.csv lookahead.csv` 为两个位置参数。
输入是已关闭的现有 game CSV（保留原始 stdout/stderr，分离 `#` 汇总后另存）；
严格接受原 20 列表头、20 行、seed 1000–1019 升序及准确的 arm、类型、边界与计数。
不接受空行、重复、缺失、多余行、不同 schema、非有限/负值、错误的 reached 标志或失败/fallback。
无效数据以异常及非零退出表示，不能补零、删掉坏行或重新采集。

报告包含全部逐 seed 的块数/消行数/字典序结果、总和、各整数门和 `csv_gate`。
双臂全部 500 块优先得到 `INCONCLUSIVE`；其他合法数据按原门得到 `PASS` 或 `FAIL`。
所有输出均保留 `final_result=NOT_EVALUATED` 和 `execution_verification=REQUIRED`：
**`csv_gate=PASS` 仅代表文件中的数字满足门，不是正式对照通过。**

## 最终判定的验证路径

正式执行者在采集前登记设计/实现最终 SHA、独立 Review、CI、协议 hash、干净 default tree、
源码/构建/依赖 hash、JDK/OS、实际 JVM 命令及全部有效配置。
明确 `TETRIS_BENCHMARK_WARMUP_GAMES=0`；不得继承别的 agent/objective/shape/warmup 设置。
确认两个直接 Java 进程顺序使用相同输出与依赖、每个 `-Xmx1g`，第二臂只获得剩余的总 1800 秒。
使用既有 timeout 终止实际 JVM；不要经 Maven 启动后只终止父进程。

采集后在 checkout 外的最终报告逐项链接并核对：

1. 事前冻结记录、实际来源及两个进程的完整命令/环境与产物 hash 一致。
2. 原始日志、提取 CSV、退出码与起止时间来自本次唯一执行；两臂已退出，零退出且未超时，
   第一臂启动至第二臂退出不超过 1800 秒；无缺失、替换或截断产物。
3. 用这两份已关闭 CSV 运行本入口并保存报告和计算器退出码。
4. 来源/CSV/进程无效取 `INVALID`，超时取 `TIMEOUT`；优先于任何数字收益。
   执行完整性全部成立后，才以 `csv_gate` 作为最终 `PASS` / `FAIL` / `INCONCLUSIVE`。
   任一执行前提未核验时保留 `NOT_EVALUATED`，不能发布最终 PASS。

这些人工证据核对是明确保留的执行责任；计算器没有验证 CI、原始日志、命令或时间的能力。
所有非 PASS 状态按原设计停止，保存失败前缀，不重试/调参/换 seed/追加候选。
本实现不增加执行框架、通用监督器或 Python 工具，也不触发正式样本。
