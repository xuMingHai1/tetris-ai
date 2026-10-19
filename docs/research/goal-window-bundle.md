# GoalWindow 派生证据持久化

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)。`scripts/write_goal_window_bundle.py` 复用已接受的 [envelope 组装](goal-window-envelopes.md)和 [baseline 投影](goal-window-baseline-format.md)，将完整输入和派生输出保存到全新目录。它不启动 JVM、warmup 或 population，不依赖尚未合并的 raw audit / prototype gates。

```sh
python scripts/write_goal_window_bundle.py --records records.json --metadata metadata.json \
  --protocol docs/research/heart-acceptance-v1.json --output /new/evidence-directory
```

records 与 metadata 格式遵循组装器接口。输入使用 UTF-8 JSON，拒绝重复键、NaN/Infinity 与不完整/错误事实；先调用冻结 #105 校验和 baseline consumer，再创建输出。三个输入按原始 bytes 保存，不重序列化；两组 envelope 和派生 baseline CSV 不增补缺失来源或 telemetry。`manifest.json` 记录六个文件的 SHA256、byte size、原 evidence_kind 及明确的 derived / 未认证 / 无 runtime 权限标志。manifest 不包含自己的 hash。

输出父目录必须存在。目录以独占 mkdir 领取，已存在的文件、目录或链接均拒绝；随后使用独占文件创建写入六个文件及 manifest。**完成条件同时要求 manifest.json 存在且 INCOMPLETE 标记不存在**，任何缺文件/hash 不符也不得消费。退出 0 仅表示本次持久化成功，不表示数值继续门通过。输入错误或 I/O 错误退出 2；创建前校验失败不留输出，写入中失败则保留 INCOMPLETE 与已有前缀，重试必须使用新路径，工具不删除或修补旧证据。即使 manifest 已写出，标记未删除也仍属不完整。

**风险：** hashes 仅绑定提交给本工具的 bytes，不证明 raw/session、class/build、seed、warmup 或 measured 来源。输入可为 synthetic，原 measured 标签也不构成认证。本工具不验证 raw audit、外部进程或 prototype 继续门，不是完整 driver；文件关闭不提供断电后的 fsync 持久性保证，不支持敌对进程同时修改新目录或输入。未来 driver 必须验证完整性/hash 并绑定实际执行证据。#94 保持未完成；#95/#96、reserved seeds 与 runtime/default 采用继续停止。

普通应用架构、构建和运行入口不变，README/AGENTS 无需同步。验证仅使用 synthetic 磁盘/CLI 与注入写入失败，不能视为实际采集或 Windows GUI 验收。
