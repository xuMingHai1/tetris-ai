# HEART 终态证书：一次登记诊断的 bounded miss

关联 [#140](https://github.com/xuMingHai1/tetris-ai/issues/140)；冻结设计见[终态分解与落子顺序证书](../design/heart-terminal-certificate.md)。本记录报告实际运行，不修改原设计、预算或停止条件。

## 结论与停止决定

2026-11-02 唯一登记诊断正常结束为 **BOUNDED_MISS**：共享 2,000 次生产枚举耗尽，没有完整落子顺序或 Q 证书。接受这个固定投入下的负面结果，停止本假设实施路径；不得再 collect、Re-run、加预算、改排序或换名重跑。不能据此断言无解，也不能解锁 #95/#96、population、reserved/provider 或 runtime/default adoption。

工作流绿色表示接线及报告正常完成，不表示构造成功。这里不是 GoalWindow paired 重跑，也没有新 seed/population。

## 来源与保留位置

- [唯一登记](https://github.com/xuMingHai1/tetris-ai/issues/140#issuecomment-6453056397)：`terminal-certificate-140-056e6d7`。
- [真实 run 41429862429](https://github.com/xuMingHai1/tetris-ai/actions/runs/41429862429)，`workflow_dispatch`、`main`、attempt 1；日志和 dispatch.json 均确认 `collect=true`。
- 执行 SHA：`056e6d7ee07382480a3ddaa0cea03909f2bc4b26`；入口记录的 checkout/default SHA 一致。
- 源码 tree：`9e12dd38f36edd14eef3303f1be6d04bd81d5b04`。
- [artifact 13133018645](https://github.com/xuMingHai1/tetris-ai/actions/runs/41429862429/artifacts/13133018645)：`terminal-certificate-41429862429-1`，20 文件，ZIP 48,931 字节。API 当前标示到期时间为 2026-12-02T10:34:58Z；应在到期前保留完整原 ZIP，不能把本摘要当作原始产物。
- ZIP SHA-256：`527aca482c1c726ac917bb1f9bf0afade5d9756d4936ee289bee85cb6ee7e59a`，下载字节与 GitHub API/上传日志一致。
- build manifest SHA-256：`80b840383964cb0ec3e3fcbac1e30b07523bf1eaa429cf9102050fd507c669b2`。
- raw session SHA-256：`1e9e445ae8f064190e900d55e7b623b961cb099f78d48765016cdc7500cbc399`。

artifact 包含 dispatch/default SHA、supervisor.log、session/report.json、四阶段 stdout/stderr、build 的 manifest/build.log/classpath/dependency-tree，以及 raw 的 observation/started/session.jsonl/completion；没有 error.json 或缺失 finish。

## 实际计数与停止点

| 项目 | 记录 |
| --- | --- |
| 几何节点 | 67 / 200,000 |
| 完整静态分解 | 27 / 128 |
| 生产枚举 | 2,000 / 2,000，全部分解共享 |
| solver_replays | 494：候选边的生产重放，不是完整证书数 |
| 完整 order / Q 证书 | 0 / 0 |
| checker_replays | 0：没有完整 order 可提交独立完整路径 checker |
| 几何停止 | CONSUMER_STOP |
| 顺序停止 | 前 26 个 EXHAUSTED；第 27 个 ENUMERATION_LIMIT |
| journal | 29 条完整事件：start + 27 attempts + finish；COMPLETE |
| JVM | 一个直接诊断 JVM，固定 -Xmx512m；8.339578629 秒 / 120 秒 |
| 父监督 | 27.666646451 秒 / 600 秒 |
| Actions job | 10:34:21–10:35:02 UTC，约 41 秒 / 10 分钟 |
| peak RSS | unavailable；512m 堆上限不表示总 RSS |

全部 attempt 的 steps/checked 为 null，facts 为空；没有终态棋盘、逐步 guard/holes 或完整 bag 证书可报告。末次累计枚举从 1,979 增至 2,000，solver replay 从 490 增至 494；finish 标记 enumeration_limit_reached=true、certificate_decomposition=0。CONSUMER_STOP 在这里来自全局枚举预算，不代表找到解。几何节点/分解还有余额不允许继续搜索。

## 核验与局限

Linux runner 为 ubuntu-24.04，JDK Oracle 27.0.1+9-62。四阶段 prepare/before/collect/after 均 EXITED、returncode=0；前后来源与原 production guard 认证均为 BUILD_AUTHENTICATED，绑定同一 manifest，父报告 provenance_authenticated=true。原 production base 为 `2d3f418afdc0454d160cda3599c5c688a8b1f670`。

下载后只读核对了完整 ZIP、manifest、build 附件与全部 raw hash；315 个源码文件与上述 tree 的字节一致（按 .gitattributes 对 cmd/bat/ps1 应用 CRLF checkout 规则）。23 个实际加载类的 origin/hash 与 manifest 编译清单一致，JDK/JVM 参数匹配；stdout/completion/report/journal 计数及固定预算一致。核对过程没有启动 Java、求解器、构建或再次 collect。

严格 Java journal reader 在已认证诊断 JVM 中返回 COMPLETE；由于没有完整 order，独立完整路径 checker 实际重放数为 0，不能写“证书重放通过”。本机复核是字节绑定与记录一致性检查，不是第二次 Linux 构建认证，也没有获得 runner 的实际 class 文件重新计算字节。来源链仍依赖原 runner 的编译/观察及前后认证，不能抵御恶意宿主或编译器。

这是固定顺序、无消行、低八行几何域中的有限投入 miss，既未穷尽静态域，也未穷尽全部落子路径。日志没有失败前缀的具体 board/type/拒绝分类，无法区分全部不匹配来自支撑、可达性、7-bag、分解顺序或其他条件；不能把“枚举先耗尽”升级为根因，更不能据此调参。对没有消行的完整四格终态族的 miss 不排除有消行的 clean 路径。

## 后续分流

| 路径 | 决定与依据 |
| --- | --- |
| 增枚举、改分解排序、扩大高度或重跑相同工具 | 不选；违反该诊断的停止条件 |
| 直接放宽在线 guard 或进入 #95/#96 | 不选；无完整证书和在线中间安全证据 |
| 只读复核既有证据，再决定是否有实质不同的问题 | 推荐；不产生新 gameplay、solver 或样本结果 |

下一步先清点既有研究中可取回的 witness/raw，核对它们是否提供消行、终态方块分解或中间义务的实证，避免把本次未覆盖的构造族直接当成可用方案。只读分析只消费既有文件；缺失项记未知，不补采、不恢复旧候选。若证据支持新假设，另交有独立问题、预算、成功与停止条件的设计 PR，经最终独立 Review/适用 CI 和正常合并后再实施。本结果文档不批准该未来设计或任何新诊断。

本次仅归档结果并更新研究导航；生产架构、运行方式及依赖未变，README/AGENTS 的架构契约无需修改。
