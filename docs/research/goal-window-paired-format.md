# GoalWindow 单局 paired 格式适配

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)，遵循
[终局窗口设计](../design/heart-goal-window.md) 与冻结的 [#105 接口](heart-paired-interface.md)。
本组件将已分割的单局原始事件转换为 paired game，不运行游戏或创建完整 envelope。

`scripts/goal_window_paired_format.py` 的纯函数：

```python
game = convert_game(events, run_order=observed_order)
```

`events` 是原始 JSONL 解析出的完整对象列表：一个 `game_started`、该局全部 `turn`、
一个 `game_complete`。保留 `schema/sequence/type/data`，序号必须连续，但不要求从1开始。
仅允许 MEASURED、20000–20079、horizon1000。warmup 和保留样本不进入本接口。
调用者必须传入实际观察的 pair order（0或1）；函数核对 seed 奇偶与臂身份，不能证明调用者
提供的顺序确实发生。全 session 调度与同JVM warmup仍需独立完整性检查。

适配器执行这些校验：

- 每个事件及 result 的 arm/seed/horizon 一致，缺失、重复、乱序、失败事件被拒绝。
- 显式读取 result 的 primary failures/fallback 及 complete 的 illegal plans；缺失不补0，
  非零拒绝转为正常死亡。原始 incomplete 前缀由 driver 保留，不作为完整 paired game。
- 从 turn 保留精确 decision nanos，校验累计成本、单调 elapsed nanos、result total/max；
  毫秒来自原始 nanos，不从舍入的平均/最大毫秒倒推。
- 对照实际 post-clear progress 和 decision facts；CSV 提供原 completion telemetry 的
  first/streak/end/post-clean摘要，与原始 nanos时间绑定。shape extrema只聚合已有占用事实，
  不重建移动、碰撞或消行。复用未修改的 `analyze_heart_paired.validate_game` 严格校验全部代数。
- clean 来自实际匹配/侵入字段；Q、candidate choice和prediction不作为完成来源。
  clean非Q仍计完成，预测成功不能填补实际未完成。第500/501块身份原样保留供原判据使用。

返回对象是独立的 #105 game，输入不被修改。输入错误抛出 `ValueError`。
本函数没有读取文件、命令行、游戏启动或写出权限，便于后续 driver 将完整审计通过的同一
bytes快照分割后调用。它不验证 candidate诊断字段、完整raw/session、来源/class/环境、
两组envelope或baseline兼容日志；原始文件与预测诊断必须由 driver 另行完整保留。
成功转换不授予 measured 身份、provenance、样本使用或 runtime 权限。

验证命令：

```bash
python3 -m unittest discover -s scripts -p test_goal_window_paired_format.py -v
```

全部输入是明确synthetic人工记录。另以20个合成seed的双臂输出接入未修改的#105分析器，
检查接口兼容与诊断权限。没有启动planner/JVM、warmup或population。
`scripts/*goal_window*.py`（包括测试）的变更由 PR selector 选择 `heart-baseline`；
共享 workflow 在生产 guard 和固定 HEART baseline 采集前自动发现并执行
`test_*heart*.py` 与 `test_*goal_window*.py`，任一失败即停止。本测试属于后者。
本地 Python、实际 CI 执行与 JDK27 验证结果仍须分别报告；该接线不启动 GoalWindow 双臂采集。

这是与#120进程监督、#121合并继续门及raw完整性审计文件独立的组件，不是完整driver交付。
剩余工作仍包括来源认证、全session审计接线、两组envelope、baseline兼容日志、完整双臂采集
workflow及整体最终Review；Python 测试发现与 selector 接线已完成，不代表完整driver交付。
#94不关闭，#95/#96、reserved与runtime/default继续停止。
生产结构、依赖和运行方式未变化；文档影响限定在本接口说明，无需新增ADR或修改生产README。
