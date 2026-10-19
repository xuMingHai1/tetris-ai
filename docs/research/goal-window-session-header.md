# GoalWindow raw 首行与 build manifest 绑定

`scripts/check_goal_window_session_header.py` 的 `check_header(header_bytes,
manifest_bytes, expected_manifest_sha256)` 为后续 driver 提供离线首行校验。
expected digest 必须由调用方从本次 prepare 结果独立保留，不能从 raw 自取。

校验外部 manifest 原始 bytes 的 digest、首条 `provenance_verified` 事件身份、
事件内 manifest 的完整 JSON 值及类型、19 个关键 loaded class 的 origin/hash。
关键类集合与 Java producer 由 source-contract 测试绑定；类的 hash 来自 manifest，
origin 按同一 checkout 的 main/test 目录规则核对。使用纯路径比较，可在 Windows
检查 Linux 文件路径，不会把证据中的路径当作本机路径访问。

```sh
python3 scripts/check_goal_window_session_header.py \
  --raw /evidence/raw.jsonl --manifest /evidence/build-manifest.json \
  --manifest-sha256 <本次prepare返回的完整digest>
```

CLI 只读取第一行，要求换行终结；退出 0 表示首行一致，2 表示无效输入。
输出绑定 header bytes 与 manifest bytes 的 SHA256，不修改输入文件。
首行之后缺失、不完整或损坏不由本工具判断；完整 raw 结构审计独立完成。

这不是来源认证：可伪造的一致 JSON 仍可能通过。没有调用 `verify_build`、检查磁盘
class/JAR、观察 JVM argv/classloader、监督进程或证明实际 seed/游戏执行。
`provenance_authenticated`、`raw_completeness_verified`、
`eligible_for_runtime_validation` 始终为 false。报告不能完成 #94 或解锁 #95/#96。
完整 driver 必须保留本次构建与进程证据并另行连接各门禁；本片段不启动采集。

普通应用结构、生产源码、冻结协议、既有分析器及 workflow 不变，故无需修改
README/AGENTS 的应用架构描述。测试仅 synthetic，不是 JDK27 或桌面验收。
