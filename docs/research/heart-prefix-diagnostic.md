# HEART 每步几何域：未接受实验与证据状态

任务 [#111](https://github.com/xuMingHai1/tetris-ai/issues/111)，设计 [#109 / #110](../design/heart-prefix-domain.md)，原实现 [PR #112](https://github.com/xuMingHai1/tetris-ai/pull/112)。

**接受范围是归档审查记录和离线日志核验工具，不接受实验实现或在线策略。** 当前没有可独立核验的域内 clean witness；#94/#95/#96 继续停止。后续 [#113](https://github.com/xuMingHai1/tetris-ai/issues/113) 找回原始文件并通过只读日志完整性核验；这不补足构建来源或 seed 生成认证，也不改变原实验否决结论。

## 保留的原实验记录

原会话报告在北京时间 2026-10-14 18:18:46，以 Windows 11 / OpenJDK 27+35 执行固定 seeds 20000–20019、depth 24、beam 128、每 seed 最多 2945 roots、60 秒外部超时；20 个 seed 全部 `BOUNDED_MISS`、0 clean witness、无 timeout/error。日志完整性核验复算 generated/admitted/pruned 为 288572 / 16541 / 3313，最低 visual error 为 2（seed 20005），四个 seed 到 depth 24。单进程 elapsed 为 0.250–4.359 秒是原 summary 中记录的墙钟时间；主机/JVM、实际编译输入和 seed 生成仍未认证，不能据此声称实验实现已获接受、不可构造或存活安全。

| 固定出处 | 含义 |
| --- | --- |
| [实测记录所声明的源码 `76e8203`](https://github.com/xuMingHai1/tetris-ai/commit/76e8203c5339ba449bd4fef2b94866b552f5a423) | 未接受实验的初始实现；source SHA 来自 supervisor 参数，不是编译 classpath 的证明。 |
| [审查版本 `4ca5b64`](https://github.com/xuMingHai1/tetris-ai/commit/4ca5b644aa1387d86e34ad0e5d0bdb9066cd7944) | 后续 supervisor 畸形记录修复、测试和摘要。与声明实测版本的全部 `src/main/java` diff 为空，已由两次源码审查核对；不替代构建 provenance 核验。 |
| [冻结设计](../design/heart-prefix-domain.md) | 原域、输入、固定投入和停止门。本文不改变设计或 HEART v1.1，不重跑诊断 population。 |

原会话登记的校验值：

| 文件 | 报告的 SHA-256 |
| --- | --- |
| `prefix-raw.log` | `ba70328ad551cecdcd65a7ce5dc63927ec7cfd6272fdb68bb301379d5a2a5025` |
| `prefix-summary.json` | `f698cda2b57850f7158568b305ccdc3667574554f256ad7f309fb3d1e337769c` |
| `prefix-metadata.json` | `9d980a434734195565b033b6ff9d9c8ae33211339a56a64af4e297d0406337fb` |

报告的 protocol SHA-256 为 `facddfb67ec0b26673e9372a1647770c9074987b624267e88fc4be5cf1870c4b`。#112 归档时未取得文件；#113 随后在原执行 worktree 的 `target/benchmark/` 找回这三个文件及二十份 `prefix-<seed>.log`，实际字节通过上述登记 hash 与逐 seed 一致性核验。原始文件只有本地保存副本，没有公开下载附件；本地路径与命令见下方，生成日志不提交源码仓库。

## 已复现的集成与证据问题

1. **冻结边界冲突。** [#112 的实际 baseline CI](https://github.com/xuMingHai1/tetris-ai/actions/runs/38551465685) 拒绝 `ai/HeartPrefixDomain.java` 与 `ai/ShapeConstructionFeasibilityBenchmark.java`：它们在允许的 `ai/benchmark/` 测量目录之外。上一轮设计允许扩展现有 feasibility，却遗漏了该目录约束。合并实现会使之后的 baseline 持续失败；本轮保留原 frozen base、guard、selector 与 v1.1，归档未接受的实现，不设置新白名单或改路由规避检查。
2. **不完整记录可误判。** 独立 Review 实际复现：给旧 supervisor 每 seed 仅一行 `prefix_result,<seed>,BOUNDED_MISS,0,0,0,0,0`，没有 sequence/depth 记录，仍可退出 0、`complete=true`。这是证据校验缺陷，不证明原采集已经发生该错误，也不能反过来相信 summary 的完整标记。
3. **归档时原始证据与构建 provenance 缺失。** #112 reviewer 当时无法读取原始文件；#113 已补做文件完整性核验。CLI `source_sha` 与外部 classpath 仍不证明实际编译输入。原记录中的 CPU / peak RSS 为 unavailable，不能填成零。

独立审查实际结论：原 #112 实现存在 P1 集成阻断和上述 P2 证据缺口，不可合并；最终仅归档与核验工具获准合并。后续日志完整性通过不能撤销集成否决或补足 provenance，也没有通过接受门的 clean witness；停止该方向的在线推进。核验出的 0/20 只支持在固定投入内停止，不能证明不可构造。

## 取得原始文件后的离线核验

[核验工具](../../scripts/verify_heart_prefix_evidence.py) 只读取现有文件，不启动 JVM、搜索或新 population：

```sh
python3 scripts/verify_heart_prefix_evidence.py <original-evidence-directory>
```

它校对登记 hash、固定 source/protocol/population/budget、三个 JSON/合并文件与二十份 seed 日志；每份须有真实格式的 24-piece / 7-bag 序列、连续完整 depth、一致的 roots/generated/admitted/unique/retained/pruned、真实终止条件和非零 negative visual error。combined raw 必须等于 seed 顺序拼接，summary 不得漏跑、重复、标为 ERROR/INCOMPLETE 或擅自获得 runtime permission。输入不满足时退出 2；仅 synthetic fixture 通过不算原数据通过。

工具输出的 `log_integrity_verified` 只代表这些文件的结构、校验值与相互一致性；CLI 固定使用登记的三份校验值，synthetic 测试所用的替代值会使 `registered_checksums_verified=false`。`build_provenance_verified`、`seed_sequence_attested`、`runtime_permission` 始终为 false。它不重新实现 seeded Java generator，不证明日志由所声明 classpath 生成，也不认证 positive witness；positive 证据另需生产 replay。

## #113 原始文件找回与实际核验

本次新增修改只登记既有日志的读取结果。使用已合并 `main@4975c5d35f9e21915b385d089c71173e10c632b9` 的未修改核验器，对以下目录运行上述命令，实际退出 0：

```text
C:\dev\IdeaProjects\tetris-ai\.git\codex-worktrees\heart-prefix-diagnostic\target\benchmark
```

全部 23 份原文件按字节复制保存至 `C:\Users\xmh\.codex\automations\tetris-ai-pr\evidence-113`，对副本再次核验退出 0；该目录保留 `verification.json`。两次输出均为 `log_integrity_verified=true`、`registered_checksums_verified=true`、20 bounded misses、0 clean witnesses、generated/admitted/pruned 288572/16541/3313、minimum visual error 2。登记三份 SHA-256 均与实际文件字节一致；二十份 seed 日志顺序拼接等于 `prefix-raw.log`。

本地路径用于定位，不保证其他主机可访问；没有把复制文件当成新的采集。未启动 JVM、重跑搜索、使用预留 population 或认证 build/seed 来源。该结果仅补足“原文件尚未取得和结构未核验”的缺口，不接受已撤回实现，不解锁 #94/#95/#96，也不宣称稳定 HEART。

文档与核验脚本是本轮新增内容；生产 Java、配置、workflow、通用 safety、冻结协议均保持当前主分支。原实验源码与修改历史保留在 #112 / 固定 commits 中，不把弃用实现包装为主分支已交付能力。
