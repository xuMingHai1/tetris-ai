# GoalWindow 派生证据包读取

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)。`scripts/read_goal_window_bundle.py` 只读消费已接受的[派生证据包](goal-window-bundle.md)，为后续 driver 提供 `read_bundle(path)`，返回验证后的 manifest 与六份文件的内存 bytes，避免调用方重新打开文件读取另一份内容。

```sh
python scripts/read_goal_window_bundle.py --bundle /existing/evidence-directory
```

要求普通目录内恰好包含 manifest 和固定六文件；存在 INCOMPLETE、缺文件、多余文件、非普通文件、直接符号链接或 Windows reparse point 均拒绝。manifest 字段与文件名固定，不将其中的路径用于磁盘访问；bytes 必须为非负整数（拒绝 bool/float），逐文件校验真实长度和 SHA256。manifest JSON 拒绝重复键和非有限数值，schema/derived/未认证/无 runtime 权限标志必须准确。

通过 hash 后，复用原 assembler、baseline projection 和冻结消费者，从原 records/metadata/protocol 重新生成两份 envelope 与 CSV，要求与包内派生 bytes 完全一致；更新 hash 不能掩盖陈旧或被修改的派生内容。原输入 bytes 可以保留空白，派生文件要求原 writer 的确定性格式。evidence_kind 必须与 metadata 一致，即使标为 measured 也保持未认证。

CLI 成功退出 0，仅输出 internally_consistent 及明确的未认证/无 runtime 权限标志；输入或 I/O 错误退出 2，不输出 traceback。工具不写入、修复或删除证据。返回的内存 bytes 是本次已校验副本；不保证检查期间遭敌对并发修改的目录，也不检查目录祖先链接或提供来源认证。

**风险：** 一致性不证明实际 raw/session、class/build、seed 或 warmup 来源；同时重造一套自洽 synthetic 数据可通过本校验。没有验证 raw audit、外部监督进程或合并 prototype 继续门，不是完整 driver，不运行 JVM/population。#94 仍未完成；#95/#96、reserved seeds 与 runtime/default 采用继续停止。

普通应用架构、构建与入口不变，README/AGENTS 无需同步。测试仅使用 synthetic 磁盘、CLI 与模拟链接属性，不是来源认证、Linux symlink 实测、population 或 Windows GUI 验收。
