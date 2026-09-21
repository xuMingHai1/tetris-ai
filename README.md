# 俄罗斯方块

> 哔哩哔哩在线视频

[![img.png](md_data/img.png)](https://www.bilibili.com/video/BV1Yx4y1S7dK)

> 游戏截图

![img1.png](md_data/img1.png)

## 开发环境

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

项目也支持 TypeSafe AI 的 Jev。Jev 不直接在全部合法落点中裸选：`BoardSimulator` 先生成所有合法 `PlacementCandidate`，本地 heuristic 复用相同评分和 tie-break 排序后只保留前 5 个安全候选，再由 Jev 在这个 shortlist 中做二次选择。运行时已知的 preview piece 会进入 `GameSnapshot`；对于每个 shortlist candidate，本地 `BoardSimulator` 还会用同一套规则计算下一块的合法落点数量，以及 heuristic 最佳下一步的客观 metrics，作为 deterministic one-piece outlook 提供给 Jev。Jev 不负责碰撞、旋转、下落或消行规则。远程结果只在方块仍保持原 snapshot 坐标时生效；如果下一次自动下落先发生，游戏会在状态变化前废弃远程结果并立即使用本地 heuristic fallback。手动输入会取消该方块尚未完成的远程决策。

Jev 必须显式启用，并通过环境变量提供 API key；默认不会发生远程调用。

Linux / macOS：

```bash
TETRIS_AI_AGENT=jev TYPESAFE_API_KEY=<your-key> ./mvnw javafx:run
```

Windows CMD：

```bat
set TETRIS_AI_AGENT=jev
set TYPESAFE_API_KEY=<your-key>
mvnw.cmd javafx:run
```

API key 不应写入仓库或配置文件。当前 Jev adapter 使用官方 `jev-latest` 模型和 `/v1/systemone` Choice API，对 HTTP 429 / 529 进行有限指数退避重试。

AI 决策不直接操作 JavaFX View；`GameWorld` 只负责把 `AiMove` 映射回现有游戏动作。方块序列由独立的 7-bag generator 提供，并支持 seed，用于可重复测试和后续 benchmark。

架构说明见 `docs/architecture/ai-engine.md`。

## AI Benchmark

项目提供独立的 headless benchmark，不启动 JavaFX View、动画、音频或实时 gravity。它使用 deterministic 7-bag seed，并直接复用 `BoardSimulator` 的合法候选和 resulting board 推进游戏，因此不会建立第二套 Tetris 规则。每个新方块在构造 AI snapshot 前会先执行一次与 `GameWorld` 相同的初始自动 `downMove()`，保证 benchmark 与桌面运行时从相同的方块坐标边界开始决策。

默认运行 1 局、最多 50 个方块、seed 从 1 开始，使用本地 heuristic：

```bash
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

可通过环境变量调整：

- `TETRIS_BENCHMARK_AGENT`: `heuristic`（默认）或 `jev`
- `TETRIS_BENCHMARK_GAMES`: 局数，默认 `1`
- `TETRIS_BENCHMARK_MAX_PIECES`: 每局最多方块数，默认 `50`
- `TETRIS_BENCHMARK_SEED`: 第一局 seed，后续每局递增，默认 `1`
- `TYPESAFE_API_KEY`: Jev benchmark 必需

例如使用相同 seed 跑 10 局本地 heuristic：

```bash
TETRIS_BENCHMARK_GAMES=10 \
TETRIS_BENCHMARK_MAX_PIECES=500 \
TETRIS_BENCHMARK_SEED=1000 \
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

Jev benchmark 会真实调用 TypeSafe API，因此会产生网络延迟和 token 使用量：

```bash
TETRIS_BENCHMARK_AGENT=jev \
TETRIS_BENCHMARK_GAMES=1 \
TETRIS_BENCHMARK_MAX_PIECES=50 \
TETRIS_BENCHMARK_SEED=1000 \
TYPESAFE_API_KEY=<your-key> \
./mvnw -Dmain.class=xyz.xuminghai.tetris/xyz.xuminghai.tetris.ai.benchmark.BenchmarkApplication javafx:run
```

输出包含每局 `pieces / lines / primary failures / fallback count / average and max decision latency`，以及局面健康度 `aggregate height / holes / bumpiness` 的 final / average / max。Jev 还会汇总 confidence 范围、token、candidate count、selected heuristic rank、top-1 agreement，以及相对 heuristic 第一名的 immediate metric delta。手动 workflow 还会把逐 decision Jev telemetry 保存为 `jev-decisions.csv`，用于在不改变策略的前提下分析 confidence 与偏离 heuristic 的关系。这些 board-health 数值直接来自生产 `PlacementCandidate`，benchmark 不重复计算规则或启发式指标。benchmark 的 latency 是完整 agent 调用耗时，用于策略评估；它不模拟桌面游戏中的实时 gravity deadline。

也可以从 GitHub Actions 手动运行 **AI Benchmark** workflow。默认参数为 `heuristic / 20 games / 500 max pieces / seed 1000`，结果会以 artifact 保存 30 天，其中包含逐局 CSV、summary、运行元数据和原始日志。 workflow 还提供 `compare` 模式：使用同一组 `games / max_pieces / seed` 先跑 heuristic，再跑 Jev，并把两组结果放入同一个 artifact，适合做成对策略对照。

Jev workflow 不会自动执行。选择 `jev` 或 `compare` 时必须同时：

1. 在 repository Actions secrets 中配置 `TYPESAFE_API_KEY`；
2. 显式勾选 `confirm_jev_cost`；
3. 保持单次潜在 decision 数 `games × max_pieces <= 200`。

这个限制用于避免误触发大量外部模型调用；普通 CI、push、PR 和定时质量检查都不会运行真实 Jev benchmark。
