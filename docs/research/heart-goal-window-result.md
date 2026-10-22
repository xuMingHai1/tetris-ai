# GoalWindow 开发/校准原型：无干预、无收益，停止

2026-10-22，任务 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94) 的唯一登记采集
`GW-b837a8f-20261022` 已完整结束。按[已接受设计](../design/heart-goal-window.md)的固定继续门，
结论为 **PROTOTYPE_FAILED，停止该候选**。不重跑，不改 Q、rank、seed 或预算救活结果；
#95/#96、reserved seeds、provider 与 runtime/default adoption 均未解锁。

## 源码、执行与证据

- 测量源码：`b837a8f0487ca2ff8741d0909490e16cb9bfffa0`（[#137](https://github.com/xuMingHai1/tetris-ai/pull/137)）。
  最终实现 head `17189b0f9fc307315ff14c9976742ac77ed73f89` 的
  [独立 Review](https://github.com/xuMingHai1/tetris-ai/pull/137#pullrequestreview-5611026846)无阻塞；
  [PR CI](https://github.com/xuMingHai1/tetris-ai/actions/runs/39742703369)、
  [benchmark 预检](https://github.com/xuMingHai1/tetris-ai/actions/runs/39742703374)和
  [主干 CI](https://github.com/xuMingHai1/tetris-ai/actions/runs/39745913001)成功。
- 唯一[测量 run 39751557172](https://github.com/xuMingHai1/tetris-ai/actions/runs/39751557172)，attempt 1。
  `heart-terminal-paired`，两臂各 80×1000，seed 20000–20079；开发20/校准60，非 fresh validation。
  单直接 JVM，两臂各100决策 warmup，seed奇偶交替；保留2700秒测量/60分钟job上限。
- JVM 于 08:58:28–09:07:30 UTC 正常 exit 0，耗时541.567秒。driver 于09:07:54 UTC结束，
  `measurement_complete=true`、`build_revalidated_before_and_after=true`、`raw_header_bound=true`；
  因原型门失败返回1。不是超时或 INCOMPLETE，也不是 CI 测试失败。
- [原始 artifact 12413628699](https://github.com/xuMingHai1/tetris-ai/actions/runs/39751557172/artifacts/12413628699)：
  25文件，ZIP 11,841,402字节，SHA-256
  `347ff25427757d1b9a50b87e18a669eb0ff165386240f1f72c019e93136b1ce4`，GitHub保留至2026-11-21。
  已恢复本地ZIP并验证CRC、ZIP hash及 `report.json` 登记的24个文件大小/hash，全部一致。
  生成数据不入Git；本页保留结果与证据摘要，artifact过期后不能假设仍可下载。

核心证据为 `measurement/raw.jsonl`、`preflight/build-manifest.json`、`raw-audit.json`、
`bundle/development.json`、`bundle/calibration.json`、`bundle/manifest.json`、
`baseline-report.json`、`prototype-report.json` 和 `report.json`。
raw SHA-256 为 `1928af2f29ffc08e90b5e1bbaa63b250c6a90a8adcf95e03fc49ddfd1ce84e38`；
prototype报告 SHA-256 为 `99b6abcc37da350f521462b9817b68c83abbf05fdab244d158f6c4c84c9c92a9`。

## 固定80配对的实际结果

| 指标 | 生产 baseline | GoalWindow candidate |
| --- | --- | --- |
| 完整局数 / 测量决策数 | 80 / 68,706 | 80 / 68,706 |
| 任意真实 clean / 前500块 clean | 0 / 0 | 0 / 0 |
| 到1000块 / 死亡 | 54 / 26 | 54 / 26 |
| pieces均值 / 中位数 | 858.825 / 1000 | 858.825 / 1000 |
| pooled nearest-rank P95 / max（ms） | 3.506593 / 10.280023 | 13.006157 / 47.070820 |
| primary failure / illegal plan / fallback | 0 / 0 / 0 | 0 / 0 / 0 |

80个配对的 pieces delta 全为0；均值/中位数、最坏损失、回归比例、baseline survivor损失、
horizon rate loss均为0。candidate-only与baseline-only clean均为0，exact paired p=1（仅诊断）。
开发和校准分别均为双臂0 clean；两份 #105 分组报告均为 `diagnostic-only-no-adoption`。

只有两个 prototype 门失败：`candidate_has_clean_by_500=0` 与 `positive_paired_clean_gain=0`，
都要求大于0。其余16门通过，包括两臂各自的50/250ms延迟门及存活/错误门；
分组分析器正常退出不等于合并prototype门通过。

独立逐行复核raw确认，candidate的68,706次测量决策全部为 `BASELINE_CHOICE`，
`IMMEDIATE_Q=0`、`PREVIEW_SETUP=0`。该固定投入内没有终局窗口干预，
只增加了枚举成本，没有产生构造或存活收益。不能据此证明HEART不可构造，或声称候选已有实际终局成功。

## 接受范围与停止边界

本次已保存完整raw、两个envelope及合并prototype报告，并由独立Review从两envelope重算
clean、存活和全部决策池延迟，核对raw选择计数及停止结论。没有重新测量。
helper/audit/bundle/prototype中的 `provenance_verified=false` 等保守字段继续保留；
它们不授予导入数据来源认证或runtime权限，也不是这次两个收益门失败的原因。
driver的来源复核不等于签名或远程主机证明，更不是seed/棋盘语义的独立完整重放认证。

按设计的“无机会/无收益即停止”记录否决。#94的实现交付与候选收益判定分开：
测试原型、接线和证据链可保留，候选不继续采集或生产化。
若未来提出不同构造假设，须先单独接受设计、冻结新协议与授权范围；
不能沿用本次失败候选调参、消费reserved seeds或直接进入#95/#96。
