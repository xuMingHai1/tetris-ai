# GoalWindow baseline 表格投影

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)。
`scripts/goal_window_baseline_format.py` 提供纯函数：

```python
text = render_baseline_tables(development, calibration, protocol_bytes)
```

输入为两份完整的 [#105 paired envelope](heart-paired-interface.md)，固定顺序为
development、calibration，以及冻结协议原始 bytes。函数先用未修改的 paired 分析器校验
两臂全部 games，再核对两组环境、evidence kind 和两臂非 games 元数据一致。
缺失、重复、保留 seed、错误顺序、计时或占用矛盾均拒绝；baseline failure/fallback/illegal
非零不能导出为正常死亡。失败抛出 ValueError，不修改输入，不读写文件，不启动 JVM。

输出仅投影 baseline arm 已有事实：game、shape summary、completion、完整 decision nanos
与 occupancy 诊断。平均/最大毫秒按既有表格精度显示，完整 nanos 不舍入；完成不依赖
candidate/Q/prediction。输出在返回前必须通过未修改的 baseline 分析器完整80局校验。
表头仅包含该分析器需要且 paired 输入实际提供的字段；不伪造 board health、lines 或
缺失的完整 benchmark telemetry。这是兼容分析视图，不能冒充原始 stdout。

首行明确标记 derived，保留 synthetic/measured 声明。warmup 行来自已校验的 envelope
声明，不证明实际同 JVM warmup；source/config/environment 相等也不是来源认证。
baseline 分析器本身不认证这些声明，也不会把该投影升级为 measured 或 runtime permission。
未来 driver 仍须保留原始 JSONL/stdout/stderr、build/class/seed 来源与实际顺序证据，并在
使用此函数前完成其认证。这个组件不创建 envelope，不完成 raw 审计或 driver/workflow，
不授予 #95/#96、reserved population 或 runtime/default adoption 权限。

```bash
python3 -m unittest discover -s scripts -p test_goal_window_baseline_format.py -v
```

测试全部使用 synthetic 数据，并实际调用冻结 baseline 分析器验证兼容性。没有游戏采集。
普通应用结构和运行方式不变，无需修改 README/AGENTS。当前 main 尚未包含 #120 的
GoalWindow Python 自动发现接线；本地测试结果与 JDK27 CI 分开报告。
