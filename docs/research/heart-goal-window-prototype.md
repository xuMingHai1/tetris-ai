# GoalWindow 原型实施状态

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)，设计来自已接受的
[#116](https://github.com/xuMingHai1/tetris-ai/pull/116) / [无状态终局窗口](../design/heart-goal-window.md)。

## 已实现的采集前置

`src/test/java/xyz/xuminghai/tetris/ai/GoalWindowCandidate.java` 只存在于测试 classpath，
不进入普通 jar 或 runtime factory。输入只有 20×10 的当前 `GameSnapshot` 与其中已知 preview；
没有 seed、bag、suffix、历史意图、环境开关或跨调用决策状态。

候选完整枚举 current，优先选择 SURVIVAL rank 最小且经生产 replay 确认的 Q。
无 immediate Q 时，对 current top-5 的合格 setup 完整枚举唯一 preview，按两级 rank
字典序选择窗口；rank 1 保留，rank 2–5 受同一次 top-1 的原 risk/budget 约束。
精确 terminal Q 可绕过原 guard，setup 不享有例外。没有窗口则调用原生产 greedy，
不复制 private selector，也不把它称作 executor fallback。异常直接传播。

观测 callback 只输出每次选择、完整枚举调用数、replay 数、各 terminal 的 Q 事实及
原 guard/top-5/risk/budget 判定，以及已检查 setup 的准入和几何事实；它不参与选择。
最多七次完整枚举是调用数量界限，不是工作量或请求 deadline；尚无实测延迟结论。

`GoalWindowCandidateTest` 使用人工 occupancy 与真实生产枚举/动作 replay 检验：

- 原 guard 拒绝的 immediate Q 与稳定 rank 选择；
- rank-1 setup、DANGER 拒绝非 baseline setup、真实下一 snapshot 重算 Q；
- 窗口丢失后重新选择；clean 非 Q 与实际 completion 口径分离；
- REQUIRED/FORBIDDEN、三类额外 holes、高度、真实消行后的 anchor；
- 缺 preview、无 landing、部分播放方向/坐标、重复及变更 snapshot。

这些 fixture 展示新选择干预及规则边界，**不证明从空棋盘可达，不是 population 数据、
存活或计时证据**。测试中的执行观测不替代未来 runner 的 actual-clean/非法计划记账。

## 第二阶段：实际落地证据与配对编排

`ai.benchmark` 同包的测试源码 `GoalWindowPairedRunner` 直接调用原
`HeadlessGameRunner.runPlanningObserved`，fallback 为 null，不复制 game loop。
每局新建 baseline/candidate；factory 不接收 seed、phase、顺序或未来块。完整计时
仍从生产 agent 调用到 terminal validation，保留每次原始 long nanos，并与生产结果
的 decisions、pieces、sum、max、failure/fallback 交叉核对；额外 observer 记账在计时外。

`GoalWindowGameEvidence` 仅由实际已验证 placement 的 post-clear board 取得
`ShapeTarget.HEART.progress`，复用原 `ShapeCompletionTelemetry` 的首次/持续完成 CSV。
逐步保存 actual progress、Q、完整候选观测与成本；测试源码中 Q helper 的 public
可见性仅供同一测试 classpath 的跨包 adapter 复用，没有扩大生产 API。
预测 setup 的 pending 记录不会增加 clean；下一已验证 turn 分别记录实际 Q、clean 非 Q、
未 clean 或 board/type 变化，游戏结束/失败时无下一观察则保留 NO_NEXT_VALIDATED_TURN。
这里的上下文比对只覆盖 board 与当前 type，不声称全 snapshot 等价。

session 先执行两臂各 19000–19009 × 10 决策 warmup，再按 seed 奇偶交替两臂。
warmup 提前死亡也失败，不缩短为成功；首个异常立即中止，不启动后续 arm/seed。
审计 wrapper 记录 delegate 是否抛错、是否返回待验证计划；只有原生产 validator
拒绝已返回计划时计 illegal plan。缺失/重复/跨臂候选观测是 evidence failure，
不会复用上一步观测或伪装成非法计划。异常对象保存已完整记录的前缀、失败分类与
primary/illegal 计数，失败局没有正常 `GameBenchmarkResult`，也不填成 bounded miss。
逐 turn 持久化已在第三阶段实现；外部总超时与全轮 driver 接线仍待下一阶段落实。

新增行为验证使用人工 HEART board、调度 stub 和最多三步的生产 loop smoke，覆盖
clean 非 Q、预测与下一实际完成、上下文变化、完整 nanos、真实 validator 拒绝、
agent 抛错、陈旧/重复观测、每局新 agent、warmup/顺序及失败即停。
调度 stub 不执行 80 局；这些测试不提供存活、收益、总体延迟或空棋盘构造结论。

## 第三阶段：原始 CLI 与构建来源

`GoalWindowPairedApplication` 是正常 `src/test/java/.../ai/benchmark` 的测试入口，
仅接受 `manifest路径 manifest原始bytes的SHA256 新JSONL路径 [--verify-only]`，
没有 seed/策略/小人口或环境覆盖开关。默认逻辑固定完整 80 配对，**本阶段不执行它**。
`--verify-only` 只验证来源并写验证证据，不创建 runner 或启动 warmup/population。
普通 jar、生产 module descriptor 和 factory 不变。

`scripts/prepare_goal_window_build.py` 在 JDK27 的干净 checkout 中，先运行未修改的
frozen guard，再使用固定 Wrapper `clean test-compile` 和 POM 中已固定版本的
dependency plugin 生成显式 test-scope classpath/dependency tree。它不启动任何游戏。
需要本地已有协议的 production-base commit；未来 workflow 仍须显式 fetch 该 SHA。
脚本记录真实 checkout/JDK/Maven、构建命令/日志、原协议与新增
[固定运行配置](heart-goal-window-config.json) 的 SHA256、全部 main/test `.class`
及依赖 JAR 的 hash。prepare 失败会使本次证书不存在，不沿用旧证书。

校验拒绝脏源码、ignored source/build 输入、被修改或多余的 class、main/test 同 FQN、
依赖 JAR 中的项目 class、class symlink、协议/配置/依赖/log/证书 hash 不一致及额外注入参数。
原 source guard 和 v1.1 JSON 未变。证书不是签名或不可信主机的远程证明；可信父进程必须
保存刚完成 clean build 返回的 manifest digest，不能使用事后自报的 source_sha 替代证据。

Java 入口在任何游戏前复核磁盘证书、实际 JDK、普通 `java -cp` 的完整 argv/classpath、
同一 application classloader、关键类真实 code source/resource 与 hash。仅用标准
class loading/source inspection，未打开或调用私有成员，未修改生产模块的 requires/exports。
两臂共享这个 JVM。固定运行配置当前没有额外 JVM flags，注入 launcher/agent 参数会失败；
使用 shell 拼接、module-path 或未登记 cache class 不属于允许的启动方式。

仅构建/校验可在干净 JDK27 checkout 中执行：

```bash
python3 scripts/prepare_goal_window_build.py --root "$PWD"
```

输出 manifest 路径及其 digest；后续父进程按其 `jdk.executable`、`classpath` 数组构造
`java -cp <完整classpath> xyz.xuminghai.tetris.ai.benchmark.GoalWindowPairedApplication`
及上述位置参数，校验时必须附加 `--verify-only`。输出必须是尚不存在的文件，禁止覆盖旧轮次。
本阶段本地只有 JDK17；Python 反例与 Java CI 中的 loaded-class/stream tests 不等于已经
完成这条 clean build + 普通 JVM 的端到端来源认证。该真实接线验证仍需下一阶段完成后记录。

原始输出是 UTF-8 `goal-window-raw-v1` JSONL，每行包含连续 `sequence`、`type` 与 `data`：

| 事件 | 内容/完整性语义 |
| --- | --- |
| `provenance_verified` | 完整 manifest/digest 与实际关键类 source/hash；此事件不代表测量完成。 |
| `verification_complete` | 仅 verify-only；`population_started=false`，不是 `session_complete`。 |
| `session_started` / `game_started` | 固定 warmup/measurement 数量与实际 phase/arm/seed/horizon。 |
| `turn` | `decision_fact` 直接提供 #105 的六项逐步字段；另有 elapsed nanos、actual progress/Q、Q accepted、候选原始观测和本步新兑现的 prediction。baseline choice 显式为 null。 |
| `game_complete` | 原 `GameBenchmarkResult`、illegal count、原 completion CSV 与全局内 prediction 列表；turn 不重复写出。 |
| `game_incomplete` | 失败分类、primary/illegal count、已记账前缀长度、completion CSV、prediction 与原因链；没有虚构正常 result。 |
| `session_complete` / `session_incomplete` | writer 再检查全部 20 warmup + 160 measured 的 seed/arm/顺序及逐步数量，全部到齐才写 complete；异常即停。 |

`turn` 的 clean 来源只读实际生产 placement，与 prediction/Q 分开；new-resolved prediction
与 game end 的全量 prediction 列表不能重复累计。每行写后 flush，额外 I/O 在生产决策计时外。
异常不会补齐失败决策的未知 nanos。强杀/外部超时可能没有最后的 incomplete 行，此时保留
已经写完的行及可能截断的尾行；**缺少最终 complete 或存在截断即不完整**，不能转为 bounded miss。
flush 保障进程中断前缀可读，不声称断电 fsync 持久性。外部父进程仍必须在 45 分钟内终止整轮、
保存 stdout/stderr/raw 并另记 INCOMPLETE，不能按 seed 重启 JVM。

新增 7 项 Java 行为检验与 6 项 Python 反例只使用小规模生产调用、当前 class 来源和临时
合成构建文件；没有执行完整 warmup、真实 clean-build 认证或人口采集。完整环境信息、baseline
兼容日志、#105 envelope 和原型继续门由后续 driver 交付，raw event 本身不是验收报告。

## 第四阶段：干净构建预检与外部进程监督

`scripts/supervise_goal_window.py` 的 CLI 始终只做 verify-only：从干净 checkout 调用既有
`prepare_goal_window_build.py`，父进程持有刚生成的 manifest digest，按登记的 JDK/完整
classpath/固定入口启动一个直接 JVM，再核对两条连续来源/验证事件与构建前后的一致性。
不提供 seed、horizon、额外 JVM flags、重试或测量 CLI 开关，也不启动 warmup/game loop。

```bash
python3 scripts/supervise_goal_window.py --root "$PWD" --output /tmp/new-goal-window-preflight
```

输出目录必须尚不存在且位于 checkout 外，避免 Wrapper clean 删除父进程记录；失败也不会
覆盖旧轮次。prepare Python/Maven 作为独立受监督进程组运行，既有构建脚本的可选
`--build-log` 将真实编译输出直接写入新外部文件。保存 `report.json`、本次 manifest/构建日志/
依赖树/classpath 副本、原始 JSONL、JVM stdout/stderr、prepare-stdout/stderr 和可在构建被取消前
读取的 `build-console.log`，登记文件 bytes/SHA256。环境记录包括 OS/kernel、CPU 型号/特征/affinity/load、
内存/cgroup 限制、Python/locale；JDK/Maven/实际 class 来源继续在 manifest/raw 中。
不导出全部环境变量或凭据。这些信息描述采集环境，不消除共享主机噪声或提供桌面保证。

构建阶段单独上限为300秒，预检 JVM 总上限为120秒；可供后续完整 driver 调用的 `launch_prepared(..., verify_only=False)`
仍固定2700秒，包含同一 JVM 的 warmup，且不允许每 seed 重启或超时重跑。该函数只返回
`EXITED/TIMEOUT/INTERRUPTED` 的进程结果；**exit 0 不等于完整测量或验收通过**。测量 driver
尚未交付，本阶段不调用测量分支。Linux 父进程在超时、SIGTERM/SIGINT 或异常退出时终止
构建或 JVM process group，短暂 TERM 清理后 KILL 剩余后代，清理期间忽略重复 TERM/INT。
启动期间先取得子进程句柄再处理取消，保留所有已写 prefix/截断尾行和
两路输出。父进程遭 SIGKILL/宿主丢失时无法保证收尾；预先写入的报告仍是 INCOMPLETE。

仅在来源复核、两条完整预检事件、零进程错误及运行后磁盘校验全部成功时，报告才成为
`VERIFIED` 且 `population_started=false`；失败/超时是 `INCOMPLETE`，population 是否启动
保持未知，不补齐缺失事实。这里的 VERIFIED 只表示构建/普通 JVM 预检，不表示 candidate
收益、全轮来源认证、稳定 HEART 或 runtime permission。

`GoalWindowPreflightTest` 在标准 Linux Wrapper 测试中建立当前提交的独立干净 checkout，
真实编译后执行上述命令，并检查实际来源、两条事件/无游戏及保留产物；嵌套构建只到
test-compile，不递归执行测试。浅 checkout 缺少 frozen base 时只 fetch 协议登记的 public
commit。另一测试把 Python 构建来源/进程反例接入标准套件。Windows/macOS 跳过这一 Linux
预检测试，不以此替代 #96。Python 反例运行真实子进程验证超时、TERM 忽略后代、父进程中断、
连续取消、构建中断、启动中断、非零退出、stdout/stderr/截断保留；Linux procfs 检查使用子进程
实际 `/proc/self/stat` PID，避免 PID namespace 差异导致存活检测假通过。合成 manifest/事件只用于格式与错误测试。

本地只有 JDK17，因此尚无本地真实 clean-build + JDK27 预检通过结论；实际端到端是否通过
须读取本 revision 的 JDK27 CI 日志。没有运行固定80局或为测量来源/候选收益提供认证。

## 剩余门禁，禁止采集

本次是 #94 第四阶段，不关闭 #94。仍需在同一问题下完成：

1. 读取真实 clean build + 普通 JVM preflight 的 JDK27 结果；完整 driver 复用监督组件，
   保留原 baseline analyzer 可核验的兼容日志及全轮失败状态。
2. 专属 driver/两个 #105 envelope/80局合并 prototype 继续门及其负面数据验证。
3. 既有 reusable workflow 内的显式双臂模式、selector 回归；原 shared-only
   heart-baseline、guard、v1.1 与 #105 分析器保持不变。
4. 最终实现 revision 独立 Review 和适用 CI 成功后，才运行一次已登记开发/校准
   20000–20079 双臂测量；单直接 JVM、45分钟总上限、60分钟 job。

本次没有执行 candidate/baseline population，也没有新增 Actions 采集路径。
没有 actual clean、净收益、存活或延迟门通过的结论。#95 / #96、30000–30199、
31000–31002 与 runtime/default adoption 继续停止。

生产 API、普通应用运行方式和构建不变，因此 README/AGENTS 不改生产契约；
test-only CLI/构建验证的运行边界记录在本页。未来 workflow 配对接线再同步顶层运行说明。
