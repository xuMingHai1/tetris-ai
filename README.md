# 俄罗斯方块

> 哔哩哔哩在线视频

[![img.png](md_data/img.png)](https://www.bilibili.com/video/BV1Yx4y1S7dK)

> 游戏截图

![img1.png](md_data/img1.png)

## 开发环境

界面改版的开发交接见 [UI V1：棋盘 + AI 驾驶舱](docs/design/ui-v1/README.md)，包含已确认的设计预览、视觉规范和实现差异；当前运行界面尚未应用该设计。

- JDK 27
- JavaFX 27
- Maven Wrapper（Maven 3.9.16）

项目使用标准 JVM + JavaFX 运行，不再使用 GraalVM / GluonFX Native Image 打包。

## 运行

Linux / macOS：

```bash
./mvnw javafx:run
```

Windows：

```bat
mvnw.cmd javafx:run
```

## 构建与测试

Linux / macOS：

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Windows：

```bat
mvnw.cmd --batch-mode --no-transfer-progress verify
```

## 质量检查

格式检查：

```bash
./mvnw spotless:check
```

SpotBugs：

```bash
./mvnw --batch-mode --no-transfer-progress -Pci-quality -DskipTests verify
```

PIT mutation testing：

```bash
./mvnw --batch-mode --no-transfer-progress -Pci-mutation verify
```

GitHub Actions 使用 self-hosted Linux x64 runner 执行构建、SpotBugs、Gitleaks、PIT 和依赖安全检查。

本项目是开源的，不会写入和创建额外业务数据。

## 桌面应用打包

项目使用 JDK 27 自带的 `jpackage` 生成自包含桌面应用，不使用 GraalVM Native Image。原生安装包必须在目标操作系统上构建，不能跨平台生成。

Linux / macOS 默认生成 application image：

```bash
./scripts/package-app.sh
```

Linux 还可以在安装了相应系统打包工具后生成：

```bash
./scripts/package-app.sh deb
./scripts/package-app.sh rpm
```

macOS：

```bash
./scripts/package-app.sh dmg
./scripts/package-app.sh pkg
```

Windows：

```bat
scripts\package-app.cmd
scripts\package-app.cmd exe
scripts\package-app.cmd msi
```

产物位于 `target/jpackage/dist`。GitHub CI 在 Linux self-hosted runner 上验证 `app-image`，Windows/macOS 原生格式应在对应平台构建。

打包脚本显式覆盖 JDK 默认的 jlink options，不执行 `--strip-debug`，因此 Linux 构建不依赖额外安装 `binutils/objcopy`。

## AI 自动玩

游戏运行后按 `F2` 切换 AI 自动玩。默认使用本地 one-ply heuristic agent；它在独立状态快照上枚举合法候选，并根据消行、堆叠高度、空洞和表面起伏评分。

项目还提供 `NextPieceHeuristicTetrisAgent` 作为纯本地 two-ply evaluation baseline：它使用与 Jev 相同的 top-5 当前候选和同一套 deterministic next-piece outlook，但不进行任何远程调用。该策略目前主要用于 benchmark，用来验证收益究竟来自 look-ahead 事实本身，还是来自 Jev 在这些事实之上的选择能力。

项目也支持 TypeSafe AI 的 Jev。`jev` 保留原有 placement-oriented 模式：`BoardSimulator` 生成合法 `PlacementCandidate`，本地 heuristic 只保留前 5 个安全候选，再由 Jev 做二次选择。`jev-action` 则使用 `ActionStateSearch` 先生成真实可达的 `AiPlan`，同样只把 heuristic 排名前 5 的安全候选交给 Jev，因此模型可以利用 `SOFT_DROP`、横移和双向旋转的组合路径，但仍不负责碰撞、旋转、下落或消行规则。两种 Jev 模式都会接收 deterministic next-piece outlook。远程结果只在方块仍保持原 snapshot 坐标时生效；如果下一次自动下落先发生，游戏会在状态变化前废弃远程结果并立即使用本地 heuristic fallback。手动输入会取消该方块尚未完成的远程决策。

远程 Jev 必须显式启用，并通过环境变量提供 API key；默认不会发生远程调用。`TETRIS_AI_AGENT` 当前支持 `heuristic`（默认）、`action`、`jev` 和 `jev-action`。其中 `action` 是完全本地的 deterministic action-native baseline。`action` 还支持独立的高层目标 `TETRIS_AI_OBJECTIVE`：`survival`（默认）完全保留现有行为；`tuck-hunter` 用于寻找/创造 action-only 机会；`build-shape` 则在同一 deterministic reachability + Risk Controller 边界内，持续让 resulting board 接近内置 Tetris-aware target。健康状态使用 `BALANCED (holes +0 / height +8 / bumpiness +8)`，普通状态使用 `CONSERVATIVE (0 / +4 / +4)`，高堆叠或 holes 较多时降为 `STRICT (0 / +0 / +2)`；`RISKY (holes +1)` 仍只用于 benchmark calibration，不会被 runtime controller 选择。当前非 survival objective 只支持 `TETRIS_AI_AGENT=action`；其它 agent 会明确拒绝该配置。

Linux / macOS：

```bash
TETRIS_AI_AGENT=jev-action TYPESAFE_API_KEY=<your-key> ./mvnw javafx:run
```

本地 Tuck Hunter：

```bash
TETRIS_AI_AGENT=action \
TETRIS_AI_OBJECTIVE=tuck-hunter \
./mvnw javafx:run
```

本地 BUILD_SHAPE（底部居中的 Tetris-aware `HEART`）：

```bash
TETRIS_AI_AGENT=action \
TETRIS_AI_OBJECTIVE=build-shape \
./mvnw javafx:run
```

`BUILD_SHAPE` 仍不读取或推断颜色，target 继续区分三种 occupancy 语义：`#` 是必须占用的视觉格，`.` 是必须保持为空的视觉背景，`+` 是允许占用的物理支撑区。内置 HEART 把 6 行视觉轮廓放在两行 support zone 上方，因此支撑块不会被误算成视觉错误。只有 Required 全部命中且 Forbidden 全部为空才算 clean completion。SURVIVAL heuristic rank 1 是风险 baseline，heuristic top-5 作为 creative planning 的 survival-quality prior；LOW / NORMAL 下只有这组候选再经过 `ObjectiveSafetyBudget` 后竞争 shape progress。Risk Controller 进入 `DANGER` 时仍暂停创作并直接返回 SURVIVAL top-1。生产 `build-shape` 保持单步 greedy shape progress。一步 `build-shape-preview` 已作为 benchmark hypothesis 验证：20×500、seed 1000 的对比中平均 visual error 仅从 20.386 降到 20.002，但到达 500 pieces 的局数从 19/20 降到 18/20，clean completion 仍为 0，因此不提升为 runtime 默认。随后 `shape-feasibility` 在 20 个 seeded 7-bag 样本上证明 HEART 在真实 action-native reachability / row-clear 规则下可构造：seed 1001 用 12 块、seed 1008 用 10 块达到 `32/32 REQUIRED + 0 FORBIDDEN`，其余样本的最佳 visual error 也都不超过 3。AI Benchmark #30 对这两个 clean witness 共 22 步做 runtime constraint audit：19/22 在 survival top-5 外、17/22 落入 DANGER、22/22 被当前 Safety Budget 拒绝，而 runtime 没有一步选择 witness outcome。AI Benchmark #31 进一步把 raw hole 按 HEART 语义拆成 `REQUIRED / FORBIDDEN / SUPPORT_ALLOWED / OUTSIDE_TARGET`：22 步累计 408 个 hole observation 中，161 个位于 FORBIDDEN、202 个位于 SUPPORT_ALLOWED，但即使只排除 clean target 明确要求为空的 FORBIDDEN holes，仍是 0/22 通过 Safety Budget，说明问题不只是 hole 语义，aggregate height 等 survival-relative delta 同样会阻断成功路径。construction safety envelope 已把成功路径的绝对 `headroom / max column height / aggregate height / raw & target-aware holes / bumpiness / current & next-piece reachable outcomes / survival rank / shape progress` 独立记录下来。随后 construction viability calibration 在每个 clean witness step 上，把成功 construction board 与同一步的 SURVIVAL top-1 board 放到完全相同的后续 7-bag 序列中比较：22/22 witness state 都走满 depth-4 bounded continuation search；20/22 在当前生产 SURVIVAL top-1 heuristic 接管后继续完整 24 块，另外两步仍分别继续 21 和 20 块；21/22 的 greedy survival horizon 不低于对应 SURVIVAL baseline。这个结果说明“相对 SURVIVAL top-1 的 holes / height / bumpiness 偏离”不能直接等同于接近 game-over。随后只在 benchmark 中评估了 `ConstructionSafetyGuard`：放开 full action-native reachable candidate set，但要求已知 preview piece 保留真实 continuation capacity，并比较 `preserve-baseline / retain-half / any-continuation` 三档。10×250、seed 1000 的同 seed 对照中，当前 runtime BUILD_SHAPE 为 10/10 到达 250 块；最严格的 `preserve-baseline` 只有 3/10 到达 250、平均 122.1 块，`retain-half` 为 2/10、平均 82.3 块，`any-continuation` 为 2/10、平均 87.2 块。三档确实把最佳 visual error 从 runtime 的 10 降到 4~6，但 clean completion 仍为 0，survival 代价不可接受。因此“一块 preview 的 reachable outcome 数”不足以作为 BUILD_SHAPE runtime safety model，不再扩大到 20×500；runtime `build-shape` 继续保持现有 top-5 + Risk Controller + Safety Budget。 下一阶段改为 recovery robustness calibration：不先定义新 guard，而是把 #48 的 clean witness state 作为正样本，把 #49 最严格 `preserve-baseline` 失败局在 game-over 前最后 5 个 selected state 作为负样本。每个 state 先让已知 preview piece 走生产 SURVIVAL top-1 recovery，再把下一块分别枚举为 I/J/L/O/S/T/Z，记录不可下落类型数、最小/平均 reachable outcomes、recovery headroom/holes，以及未知下一块经过 SURVIVAL top-1 后的最差 headroom/holes。该阶段只验证这些事实是否能区分“可持续 construction”与“累计退化”，不会从 observed extrema 直接生成 runtime threshold。 首轮结果显示出明确差异：22 个 clean-witness 样本的 recovery headroom 为 8~17、worst-case post-unknown headroom 为 8~16；35 个 failed-tail 样本对应范围分别为 -1~2 与 -1~2，且在距离 game-over 4 个成功 placement 时 recovery headroom 仍最多只有 2。相比之下，min unknown reachable outcomes 在两类之间于 9 发生重叠，recovery holes 也在 25~33 区间重叠，因此“恢复后的垂直余量”目前比 branching count 或 hole count 更有区分力。这个分离仍只来自两个 known clean witness 与 7 个失败 seed 的尾部状态，下一步必须加入更长 failure lead-time 与长期存活的 runtime control states 检查 false positive，不能直接把 8/2 写成 guard 阈值。 本轮 `recovery-reserve-lead-time` 因此保持 #50 的 probe 不变，只改变样本设计：失败 guard 每局最多回看 game-over 前 30 个成功 placement；当前生产 BUILD_SHAPE 在相同 10×250 seed 上作为 healthy control，仅当整局到达 piece limit 时才纳入，并按每 5 个 decision + 最后 30 个 decision 做 deterministic sampling。benchmark 对 recovery headroom / worst-case post-unknown headroom 分别扫描 cutoff 2/4/6/8，只报告 clean/control warning sample、control warning game、失败局检测覆盖率与最早 warning lead-time；这些仍是 calibration sweep，不代表采用任一阈值。 首轮 10×250 结果表明单独的 headroom cutoff 仍不足以直接采用：`recovery-headroom<=2` 对 22 个 clean witness 为 0 warning，对 740 个 healthy control sample 仅 4 次 warning（0.54%，集中在 seed 1003 的连续 233~236 步），同时捕捉 7/7 失败局，最少提前 6 步、平均提前 16.3 步；但 cutoff 提高到 4/6/8 后 healthy false positive 快速上升。值得继续验证的是组合信号：上述 4 个 healthy 低-reserve 状态的 recovery holes 只有 10~11，而同批失败轨迹在 `recovery-headroom<=2` 且仍可恢复的状态中 recovery holes 为 15~60。这个 holes 分离只来自当前 calibration seed 集，不能直接推导 runtime 条件。`RecoveryReserveValidationApplication` 因此把假设冻结后再做 out-of-sample 验证：preview 不可恢复直接记为 warning；preview 可恢复时只有 `recoveryHeadroom<=2 && recoveryHoles>=15` 才 warning。seed 2000–2019 的最终结果为 known clean 0/22 warning；15/20 当前 runtime 局达到 1000 pieces，healthy sampled controls 0/3360 warning；preserve-baseline 的 16 个失败局 16/16 被检测，lead-time 为最少 5 / 平均 18.813 / 最多 29 placements。这个结果证明了独立数据上的 class separation，但同一轮还有 5/20 当前 production BUILD_SHAPE 局本身没达到 1000 pieces，因此尚未证明 signal 能预测生产策略自己的失败。`RecoveryReserveRuntimeValidationApplication` 随后在全新 seed 3000–3039 上只运行生产 `BuildShapeActionPlanningAgent`，frozen signal 仍不变。40×1000 最终得到 28 个 healthy / 12 个 failed；healthy sampled states 6272 中仅 7 次 warning（0.1116%，分布于 4/28 healthy games），12/12 production failures 全部被检测，首次 warning lead-time 最少 0 / 平均 19 / 最多 29 placements，其中 10/12 至少提前 12 步。healthy seed 3007 在 decision 100/105 warning 后仍活到 1000，说明单次 warning 可以是 transient；seed 3004 的 998–1000 warning 则受 1000-piece horizon censoring。下一轮 `RecoveryReservePersistenceValidationApplication` 随后在全新 seed 4000–4029 上逐 successful placement probe。30×1000 得到 16 个 healthy / 14 个 failed；healthy 3/16 games 出现 warning，共 18 个 episode 且 18/18 全部恢复，平均长度 3.944、最长 15；14/14 failed 全部被检测，55 个 failed episode 中 41 个也恢复过，最终每个失败局都有 terminal episode，但 terminal episode 最短只有 1、最长 22，failed recovered episode 最长 18。首次 warning lead-time 最少 0 / 平均 91.643 / 最多 662。这说明 episode duration 与最终失败高度重叠，不能再发明“连续 N 次 warning”阈值。下一轮 `RecoveryInterventionComparisonApplication` 因此改为 paired action experiment：全新 seed 5000–5029 上，baseline 保持 production BUILD_SHAPE；intervention arm 仅当本次 BUILD_SHAPE selected resultingBoard 触发 frozen warning 时，把这一手替换为现有 deterministic SURVIVAL top-1，下一手重新正常 BUILD_SHAPE。实验同时比较 survival、介入次数、warning clearing、construction quality 与 probe latency，仍不修改 production runtime。

Windows CMD：

```bat
set TETRIS_AI_AGENT=jev-action
set TYPESAFE_API_KEY=<your-key>
mvnw.cmd javafx:run
```

API key 不应写入仓库或配置文件。当前 Jev adapter 使用官方 `jev-latest` 模型和 `/v1/systemone` Choice API，对 HTTP 429 / 529 进行有限指数退避重试。

AI 决策不直接操作 JavaFX View；`GameWorld` 接收统一的 `AiPlan` 并按顺序映射到现有游戏动作。placement-oriented agent 会先通过 adapter 转换成 `AiPlan`，action-native agent 则可以直接返回动作序列。方块序列由独立的 7-bag generator 提供，并支持 seed，用于可重复测试和后续 benchmark。

架构说明见 `docs/architecture/ai-engine.md`。

## AI Benchmark

项目提供独立的 headless benchmark，不启动 JavaFX View、动画、音频或实时 gravity。placement-oriented 策略通过 `BoardSimulator` 的合法候选/resulting board 推进游戏；action-native 策略通过 `ActionPlanSimulator` 重放 `AiPlan`，后者继续复用 `ActionStateSearch`、`Tetris` 和 `BoardRules`。benchmark 不建立第二套 Tetris 规则。每个新方块在构造 AI snapshot 前都会先执行一次与 `GameWorld` 相同的初始自动 `downMove()`，保证 benchmark 与桌面运行时从相同坐标边界开始决策。

默认运行 1 局、最多 50 个方块、seed 从 1 开始，使用本地 heuristic：

```bash
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

可通过环境变量调整：

- `TETRIS_BENCHMARK_AGENT`: `heuristic`（默认）、`lookahead`、`jev`、`action`（deterministic action-native）、`tuck-hunter`（固定 risk profile）、`adaptive-tuck-hunter`、`build-shape`、`build-shape-preview`（benchmark-only 一步 preview lookahead）、`action-provenance`（纯本地 action-only 分布扫描）或 `jev-action`
- `TETRIS_BENCHMARK_GAMES`: 局数，默认 `1`
- `TETRIS_BENCHMARK_MAX_PIECES`: 每局最多方块数，默认 `50`
- `TETRIS_BENCHMARK_SEED`: 第一局 seed，后续每局递增，默认 `1`
- `TETRIS_BENCHMARK_RISK_PROFILE`: `strict` / `conservative`（默认）/ `balanced` / `risky`，仅影响 `tuck-hunter` benchmark
- `TYPESAFE_API_KEY`: `jev` / `jev-action` benchmark 必需

BUILD_SHAPE 还提供独立的 construction feasibility 入口。它不是 gameplay agent，不读取 `TETRIS_BENCHMARK_AGENT`；给定 seeded 7-bag 序列后，用真实 `ActionStateSearch -> PlacementCandidate -> BoardRules` 扩展可达棋盘，并用 bounded beam 保留搜索前沿：

```bash
TETRIS_BENCHMARK_GAMES=20 \
TETRIS_BENCHMARK_MAX_PIECES=24 \
TETRIS_BENCHMARK_SEED=1000 \
TETRIS_BENCHMARK_FEASIBILITY_BEAM_WIDTH=128 \
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.ShapeFeasibilityApplication javafx:run
```

该模式若找到 clean completion，会输出逐 piece 的 `AiPlan` witness，并立即运行 runtime constraint audit 与 construction safety envelope；未找到只表示当前 depth / beam 预算没有找到构造路径。constraint audit 写入 `shape-witness-audit.csv`；成功路径的绝对 board-health 与 immediate continuation facts 写入 `construction-safety-envelope.csv`；同 future piece sequence 下的 witness-vs-SURVIVAL viability calibration 写入 `construction-viability.csv`。envelope extrema、greedy horizon 和 bounded-search horizon 都是 observed evidence，不应直接复制成新的安全阈值。

例如使用相同 seed 跑 10 局本地 heuristic：

```bash
TETRIS_BENCHMARK_GAMES=10 \
TETRIS_BENCHMARK_MAX_PIECES=500 \
TETRIS_BENCHMARK_SEED=1000 \
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

`jev` 与 `jev-action` benchmark 都会真实调用 TypeSafe API，因此会产生网络延迟和 token 使用量。Action-native Jev 可这样运行：

```bash
TETRIS_BENCHMARK_AGENT=jev-action \
TETRIS_BENCHMARK_GAMES=1 \
TETRIS_BENCHMARK_MAX_PIECES=50 \
TETRIS_BENCHMARK_SEED=1000 \
TYPESAFE_API_KEY=<your-key> \
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

输出包含每局 `pieces / lines / primary failures / fallback count / average and max decision latency`，以及局面健康度 `aggregate height / holes / bumpiness` 的 final / average / max。两种 Jev 模式都会汇总 confidence、token、candidate count、selected heuristic rank、top-1 agreement，以及相对本地 shortlist 第一名的 immediate metric delta。`jev-action` 还会在 shortlist 已经确定之后，按与 reachability benchmark 相同的 post-lock/post-row-clear outcome 口径标记哪些候选是旧 rotate-then-shift placement 路径无法达到的 action-only outcome，并统计候选占比与实际选择率；这个 provenance 只进入 telemetry，不发送给 Jev，也不参与排序。BUILD_SHAPE 除逐 decision telemetry 外，还输出逐局 `best_visual_error / max_required_with_zero_forbidden / min_forbidden_at_full_required / clean_completion`，aggregate summary 会统计 `games_error_le_8 / <=4 / ==0`，避免跨所有 decision 的平均 visual error 掩盖最佳构造状态。`compare-shape-guard` 额外用相同 seed 比较当前 runtime BUILD_SHAPE 与三档 benchmark-only construction guard，并输出 guard checked/rejected、selected survival rank、baseline/selected preview reachable outcomes 和 selected headroom。首轮 10×250 已证明 one-piece continuation capacity 不能单独承担 runtime safety，因此该模式主要保留用于复现和后续 guard 迭代。手动 workflow 会把这些数据分别保存为 artifact CSV。benchmark latency 是完整 agent 调用耗时，不模拟桌面游戏的实时 gravity deadline。

GitHub Actions 还会对 **同仓库、目标为当前 default branch 的 PR** 自动选择一个最相关的 deterministic AI benchmark。paired recovery intervention 实验改动优先运行 `recovery-intervention-comparison (30 × 1000, seed 5000)`；recovery warning persistence 验证改动运行 `recovery-reserve-persistence-validation (30 × 1000, seed 4000, every successful placement)`；生产 BUILD_SHAPE recovery failure 验证改动运行 `recovery-reserve-runtime-validation (40 × 1000, seed 3000)`；out-of-sample recovery-reserve 验证改动运行 `recovery-reserve-validation (20 seeds, healthy 1000 / failure 500, seed 2000)`；recovery-reserve lead-time 校准改动运行 `recovery-reserve-lead-time (10 × 250)`；recovery-robustness 校准改动运行 `recovery-robustness (10 × 250)`；construction-guard 实验改动运行 `compare-shape-guard (10 × 250)`；其它 BUILD_SHAPE / shape 改动运行 `shape-feasibility (20 seeds, depth 24, beam 128)`；objective risk/safety 改动运行 `compare-adaptive (10 × 250)`；action-native reachability / shared board facts 改动运行 `action-provenance (10 × 250)`；与 AI benchmark 无关的 PR 不额外消耗 runner。一个 PR revision 最多自动跑一个 benchmark，新的 push 会取消该 PR 的旧 benchmark run。外部 fork PR 不会在 self-hosted runner 上执行这些 benchmark，自动路径也永远不会调用 Jev。

也可以从 GitHub Actions 手动运行 **AI Benchmark** workflow。默认 gameplay 参数为 `20 games / 500 max pieces / seed 1000`，结果会以 artifact 保存 30 天。`compare` 使用同一组 seed 运行 `heuristic / lookahead / jev`；`compare-action` 运行 `action / jev-action`；`compare-objective` 完全本地运行 `action / tuck-hunter`；`compare-adaptive` 比较 `action / adaptive-tuck-hunter`；`compare-shape` 比较 `action / build-shape-heart`；`compare-shape-preview` 保留为已验证的 greedy-vs-preview 复现实验；`compare-shape-guard` 比较当前 BUILD_SHAPE 与三档 preview-continuation guard；`recovery-robustness` 比较 known clean witness 与失败 guard 尾部状态的 recovery robustness，并输出 `recovery-robustness.csv`；`recovery-reserve-lead-time` 进一步加入 30-step failure history 与长期存活 runtime control sampling，输出 `recovery-reserve.csv` 和 cutoff/lead-time summary；`recovery-reserve-validation` 冻结联合信号并在独立 seed / 更长 healthy horizon 上验证，输出 `recovery-reserve-validation.csv`；`recovery-reserve-runtime-validation` 使用相同 frozen signal 直接验证当前生产 BUILD_SHAPE 的 healthy / failed trajectories，输出 `recovery-reserve-runtime-validation.csv`；`recovery-reserve-persistence-validation` 逐 placement 观察 frozen warning，并输出 warning / episode / per-game 三类 CSV，用于区分 transient recovery 与持续到失败的 episode；`recovery-intervention-comparison` 在相同 fresh seeds 上成对比较 production BUILD_SHAPE 与 warning-triggered 单步 SURVIVAL top-1 intervention，输出 per-warning decision 与 paired per-game CSV。`shape-feasibility` 是独立的 bounded construction search，使用 `games + seed` 作为 7-bag 样本，使用单独的 `feasibility_depth`（默认 24）和 `feasibility_beam_width`（默认 128），不会误用 gameplay 的 500-piece 默认值；结果包含 `shape-feasibility.csv`，若找到 clean completion 还会包含 `shape-feasibility-witness.csv`、`shape-witness-audit.csv`、`construction-safety-envelope.csv` 和 `construction-viability.csv`。viability 默认使用 search depth 4 / beam 32 与 greedy depth 24，也可通过 workflow inputs 调整。`calibrate-objective` 用于固定风险档校准，`action-provenance` 用于 action-only 分布扫描；这些本地模式都不调用 Jev。

Jev workflow 不会自动执行。选择 `jev`、`jev-action`、`compare` 或 `compare-action` 时必须同时：

1. 在 repository Actions secrets 中配置 `TYPESAFE_API_KEY`；
2. 显式勾选 `confirm_jev_cost`；
3. 保持单次潜在 decision 数 `games × max_pieces <= 200`。

这个限制用于避免误触发大量外部模型调用；普通 CI、push、PR 和定时质量检查都不会运行真实 Jev benchmark。
