# GoalWindow 两组 envelope 组装

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94) 与已接受的[设计](../design/heart-goal-window.md)。本组件只格式组装，不执行测量或认证来源。

`scripts/goal_window_envelopes.py` 的纯函数 `assemble_envelopes(records, metadata, protocol_bytes)` 返回 `development`、`calibration` 两个完整的[冻结 #105 envelope](heart-paired-interface.md)。输入为全部 160 份已转换的单局事实：每项显式包含 `arm`（小写 `baseline` / `candidate`）及 `game`；单局格式转换见 [paired adapter](goal-window-paired-format.md)。记录必须保持实际观察顺序，从 20000 到 20079，每 seed 两臂按奇偶交替。缺失、重复、越界、重排及错误 pair order 均拒绝，不靠排序修复错误顺序。

`metadata` 是同一次 session 的 #105 header，包含 `schema`、`protocol_sha256`、`evidence_kind`、`timing_scope`、`input_boundary`、`reset_strategy_per_game`、`environment`、`arms`，不含 `population`。两个 arm header 各显式提供 `source_sha`、`config`、`config_sha256`、`production_base_sha`、`warmup`，不含 `games`。两臂 source SHA 必须一致；其余配置、环境、完整 100 决策 warmup 摘要、协议 digest 和全部单局事实交由未修改的 #105 consumer 校验。两组引用同一份 warmup 摘要，不表示各自重做 warmup。没有默认失败值或推导出来的环境/来源记录。

开发 20000–20019、校准 20020–20079 分别按原协议校验；全部原始 decision nanos、post-clear clean、游戏结果及 arm 身份保持不变。失败/非法计划/fallback 非零时拒绝组装完整证据。有效诊断数据即使未通过采用级数值门仍可返回；调用方必须读取各组及后续合并原型报告，不能把函数返回当作继续门通过。返回值与输入及另一组独立，无可变对象别名。

**边界与风险：** `evidence_kind` 保留调用方声明，`measured` 标签不构成实际测量认证。构建/class/config 的语义来源、warmup 真实执行、原始事件完整性和身份真实性仍须由未来完整 driver 独立验证；单局 arm 是显式输入，不能从棋盘结果证明其真实性。本函数不读取 raw、环境或进程，不代替 raw audit，不提供 CLI/文件持久化/证据 hash。来源认证、完整 driver 和 workflow/selector 仍未完成，两个开发/校准 envelope 不授予 runtime validation 权限；#95/#96、保留样本和 runtime/default 采用继续停止。

验证使用纯 synthetic records，包括 raw 单局 adapter → 组装 → 已合并 [baseline 表格投影](goal-window-baseline-format.md) → 原 baseline consumer 的兼容链路。未执行 JVM、warmup 或任何 population。新增普通 Python 模块不改变生产架构、包职责、运行入口或构建；README/AGENTS 无需同步。
