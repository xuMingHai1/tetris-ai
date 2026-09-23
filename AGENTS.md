# Tetris AI Agent Guide

## Project Mission

本仓库当前是基于 Java 27 + JavaFX 27 的桌面俄罗斯方块项目。现有实现先保证游戏规则、状态和 UI 行为稳定；后续引入 AI 能力时，应建立清晰的游戏状态/决策边界，而不是把搜索、评估或自动控制逻辑直接耦合到 JavaFX View。

项目使用 Java Module System，模块名为 `xyz.xuminghai.tetris`。

## Source of Truth

GitHub 当前 default branch 是源码事实来源，不假设主分支名称固定。

开始新的开发或 Review 工作前：
- 先确认 repository 当前 default branch、最新 SHA 和相关 PR 状态。
- 已 merged / closed 的旧 PR branch 不再继续使用；从最新 default branch 新建短生命周期 branch。
- 历史聊天、旧代码片段、旧 ZIP、旧 branch 和旧 PR 仅作背景参考，不能覆盖当前源码。
- 如果源码、README、AGENTS 或未来的 architecture / ADR 文档冲突，必须明确指出，不静默猜测。

## Current Structure

- `xyz.xuminghai.tetris.core`：俄罗斯方块领域模型，包括 Cell、七种方块、移动、旋转、复制、共享 `BoardRules`，以及可注入的 `PieceGenerator` / deterministic `BagPieceGenerator`。这里应尽量保持与 JavaFX Scene/View 无关；当前颜色仍使用 JavaFX `Color`，修改该边界前需要评估兼容性和 AI/测试影响。
- `xyz.xuminghai.tetris.ai`：headless 决策边界。只消费不可变 `GameSnapshot`；snapshot 可携带运行时已知的 preview piece。`BoardSimulator` 统一生成合法 `PlacementCandidate`（动作、落地后棋盘和客观指标），并负责从真实 post-spawn gravity 边界计算 preview piece 的 deterministic one-piece outlook；具体 `TetrisAgent` 只负责从这些确定性事实中选择 `AiMove`。不得依赖 JavaFX Property、Animation、Audio、Robot 或 View。当前包含本地 `HeuristicTetrisAgent`、用于评估 deterministic next-piece 收益的 `NextPieceHeuristicTetrisAgent`，以及 opt-in 的 `JevTetrisAgent`；Jev 只在本地 heuristic 排序后的前 5 个合法候选中选择，并接收下一块的合法落点数量和本地最佳下一步 metrics，避免把全部合法落点或第二套游戏规则交给远程模型；`TypeSafeSystemOneClient` 只负责官方 HTTP API、JSON、认证和 429/529 重试；`AiDecisionExecutor` 负责在 virtual thread 上执行可能阻塞的 Agent、提供 heuristic fallback，并用 generation 标识最新请求。它不是通用 Agent/Plugin 框架。
- `xyz.xuminghai.tetris.ai.benchmark`：headless evaluation 工具。使用 seeded `BagPieceGenerator` 和生产 `BoardSimulator` / `PlacementCandidate` 推进状态；新方块在生成 AI snapshot 前必须先执行与 `GameWorld` 相同的一次初始自动 `downMove()`，保证候选可达性从相同坐标边界开始。记录 gameplay outcome、decision latency、fallback，以及 selected candidate 的 aggregate height / holes / bumpiness 局面健康度；Jev 额外记录 confidence/token usage、selected heuristic rank 和相对 heuristic top candidate 的 immediate metric delta，并可输出逐 decision CSV。telemetry 只用于评估，不能反向影响 gameplay decision。benchmark 不启动 JavaFX runtime，也不复制游戏规则或重新计算这些指标。
- `xyz.xuminghai.tetris.game`：游戏规则和运行状态，包括网格、计分、等级、时间线、输入动作和动画协作。规则变化应优先在这里表达，不把规则复制到 View。
- `xyz.xuminghai.tetris.view`：JavaFX 展示层。负责观察状态并渲染，不应成为游戏规则的第二事实来源。
- `xyz.xuminghai.tetris.util`：音频和版本等辅助能力。不要把业务规则沉淀为通用 util。
- `TetrisApplication`：JavaFX 应用启动和顶层输入装配。保持 bootstrap 职责，不把核心游戏算法堆积到入口类。
- `src/main/resources`：CSS、图片、国际化、音频等运行资源。WAV/MIDI/图片按二进制资源处理。

AI 已通过 `GameSnapshot -> TetrisAgent -> AiMove` 建立最小边界。新增搜索深度、look-ahead 或其他算法时应复用该状态/动作契约，不把 JavaFX runtime 引入搜索路径，也不提前建立通用 Agent/Plugin/DSL 框架。

## Build and Tooling

- JDK: 27
- JavaFX: 27
- Maven: 通过 Maven Wrapper 固定为 3.9.16
- 标准验证：
  `./mvnw --batch-mode --no-transfer-progress verify`
- SpotBugs：
  `./mvnw --batch-mode --no-transfer-progress -Pci-quality -DskipTests verify`
- PIT：
  `./mvnw --batch-mode --no-transfer-progress -Pci-mutation verify`

Windows 对应使用 `mvnw.cmd`。

不要依赖开发机全局 Maven 版本；CI 和文档应优先使用 Wrapper。

## Development and Review Rules

- 未经用户明确授权，不直接 push default branch。
- 默认使用短生命周期 branch，并通过 Draft PR 交付。
- 一个 PR 尽量只处理一个主要问题，不混入无关重构、格式化或资源清理。
- PR 仍 open 且属于同一问题时，可以继续使用当前 branch；PR merged / closed 后停止使用旧 branch。
- Merge 由用户执行；未经授权不 merge，也不启用 auto-merge。
- GitHub inline comment 视为正式 Review 意见。接受则修改并说明；不接受则给出技术原因；默认不 Resolve conversation。
- 创建或更新 PR 后检查最终 `default-branch...head` diff，确认没有 unrelated changes、误删资源或遗漏测试/文档。
- CI 失败先定位真实原因，不为让 CI 变绿而关闭检查或修改无关代码。

## Design Principles

- 优先保持游戏规则与 UI 表现分离。View 可以订阅状态，但不要复制碰撞、计分、消行或方块状态转换规则。
- 优先使用 JDK / JavaFX 原生能力；已有项目能力能表达需求时，不增加只替代几行代码的新依赖或抽象。
- 小功能保持简单。只有存在真实复用、独立语义、复杂失败边界或测试价值时才提取 helper / abstraction。
- 修改 `core` 或 `game` 的状态语义时，必须考虑已有测试以及未来 AI 消费游戏状态的影响。
- 远程或可能阻塞的 AI 实现可以实现 placement-oriented `TetrisAgent` 或 action-native `AiPlanningAgent`，但都必须通过 `AiDecisionExecutor` 执行；不要在 JavaFX application thread 或 `GameWorld` 内直接进行网络 I/O。
- 远程 AI 的结果只能作用于它读取的原始 snapshot：应用前必须校验当前方块坐标仍完全一致；下一次自动下落若先到达，则必须先废弃远程 generation 并在状态变化前执行本地 fallback。手动输入优先并取消该方块的 pending remote decision。
- AI 策略不得自行重新实现碰撞、旋转、下落或消行规则；placement 候选合法性来自 `BoardSimulator`，action-native 路径合法性来自 `ActionStateSearch`，落地事实统一复用 `PlacementCandidate`/`BoardRules`。策略可以改变评分或选择方式，但不能建立第二套游戏规则。
- AI objective 层只能改变 deterministic reachable plan 的偏好，不能自行定义移动合法性、碰撞或消行。
- `BUILD_SHAPE` 等 creative objective 必须基于生产 `resultingBoard` 的 post-row-clear 事实评分，不能维护第二套棋盘；当前 board state 只有 occupancy 时不得推断或伪造 settled-piece color，颜色目标要等显式 color-aware state model。Creative target 必须显式区分视觉 REQUIRED、视觉 FORBIDDEN 与允许的物理 SUPPORT，不能把“目标格都被占用”误当成完整图形；clean completion 还必须保证 FORBIDDEN 为空。BUILD_SHAPE 保留 heuristic top-5 作为 benchmark 验证过的 survival-quality prior，同时记录全量 action-native reachable 数量用于观测；SURVIVAL top-1 始终是风险 baseline，任何非 baseline 候选都必须通过相对 SURVIVAL top-1 的 `ObjectiveSafetyBudget`，不能用 survival rank 本身代替安全判断。不要再次扩大到 full reachable envelope，除非有新的 benchmark 假设和证据；现有实验显示它没有改善 clean completion 且明显损害长期生存。目标机会不存在或超出 budget 时必须回退到 SURVIVAL。`ObjectiveRiskController` 只能根据 SURVIVAL top-1 的结果棋盘选择风险档位，不能根据待评估 objective candidate 反向改变 budget。runtime 可在 STRICT / CONSERVATIVE / BALANCED 间切换，但必须保持 `additional holes == 0`；RISKY 仍只用于 benchmark calibration。BUILD_SHAPE 在 DANGER 下必须停止 creative deviation 并返回 SURVIVAL top-1。
- Jev 必须通过 `TETRIS_AI_AGENT=jev` 或 `TETRIS_AI_AGENT=jev-action` 显式启用，`TYPESAFE_API_KEY` 仅作为凭据，不得因为 key 存在就自动开启远程调用；secret 不写入仓库、不输出到日志。远程 Choice 只能接收 deterministic reachability 之后的本地 heuristic shortlist（当前最多 5 个）；shortlist 复用 `HeuristicTetrisAgent` 的既有评分，不复制第二套权重。
- TypeSafe 当前没有 Java SDK，Java 集成使用 JDK `HttpClient` 调用官方 System One HTTP API；JSON 使用 Jackson 3，不手写 JSON parser。
- Benchmark 默认必须完全本地且 deterministic；真实 Jev benchmark 只能显式选择并使用环境变量 secret，CI 默认不得调用外部模型或消耗 provider quota。
- Benchmark 状态推进必须复用生产规则：placement 策略通过 `BoardSimulator`，action-native 策略通过 `ActionPlanSimulator`/`ActionStateSearch`，最终统一使用 `PlacementCandidate`/`BoardRules` 的结果；不得为 benchmark 单独实现旋转、碰撞、下落或消行规则。
- 不重新引入 GraalVM / GluonFX Native Image 的二进制和配置，除非出现明确的新打包需求并单独评审。
- 不把生成文件、IDE 状态、日志或本地环境文件提交到仓库。

## Tests and Quality

- 修改纯游戏规则时优先补充无 UI 的单元测试，使测试可以稳定运行且适合 PIT。
- 不为了覆盖率数字编写只验证 getter/setter 的低价值测试；重点覆盖移动、旋转、碰撞、消行、计分、状态转换和边界条件。
- JavaFX UI/动画测试只有在行为风险值得时才引入；避免为了测试而启动完整 UI Toolkit。
- SpotBugs 是静态质量门禁；发现问题时先判断真实缺陷或合理 false positive，再决定修代码或做最小范围排除。
- PIT 用于衡量核心规则测试是否能发现行为改变。不要通过扩大 excludes 来掩盖缺少测试。
- 修改 dependencies / build plugins 时检查 CI 的 dependency security 流程是否仍适用。

## CI / Self-hosted Runner

GitHub Actions 使用 `[self-hosted, linux, x64]`。

`.github/workflows/ai-benchmark.yml` 仅允许 `workflow_dispatch` 手动运行，不属于普通 CI。默认运行本地 heuristic baseline；`compare` 模式会对相同 seed/参数顺序运行 heuristic、纯本地 lookahead 与 Jev；`compare-objective` 完全本地比较 action survival 与固定 profile 的 tuck-hunter；`compare-adaptive` 使用相同 seed 比较 action survival 与 runtime adaptive tuck-hunter；`compare-shape` 使用相同 seed 比较 action survival 与 BUILD_SHAPE，并生成包含 shape progress telemetry 的同一份 artifact。Jev benchmark（含 `compare` 中的 Jev 部分）只有显式确认外部调用成本并存在 `TYPESAFE_API_KEY` repository secret 时才能运行，且单次最多 200 个潜在 Jev decision。benchmark 结果应上传 artifact，不提交生成结果到源码仓库。

由于仓库是 public repository：
- 外部 fork PR 不允许在 self-hosted runner 上执行任意代码。
- 保留 workflow 中对同仓库 PR 的限制，除非安全模型被明确重新设计。
- Runner 需要 Docker（Gitleaks / OSV）、Python 3 和基本 Unix 工具。
- Java 由 `actions/setup-java` 安装；Maven 使用仓库 Wrapper。

## Source Code Comments

注释应解释职责、约束和非显然原因，而不是逐行翻译代码。

- 新增或修改核心类型时，检查类/方法 Javadoc 是否准确描述职责和重要行为。
- 旋转状态、网格边界、计分、动画/线程约束、JavaFX thread 要求等不容易从代码直接看出的语义，应在必要位置说明原因。
- 修改行为时同步更新失效注释。
- 不要求简单 getter、明显赋值都写模板注释。

## Documentation Impact

UI 改版采用用户已确认的 [UI V1 设计基准](docs/design/ui-v1/README.md)。该目录是开发交接资料，HTML 使用示例状态；不得将其碰撞、计分、按键或 AI 模式示意当作当前生产契约。实施前核对文档中的能力差异，并保持已确认的浅灰靛蓝与三栏方向。

Any change that alters architectural understanding must also evaluate its documentation impact.

判断标准：是否会改变后来者对系统结构、职责、依赖关系、运行方式或关键设计决策的理解？

- 普通 bugfix / 小型测试补充通常无需额外 architecture 文档。
- 包职责、AI 边界、游戏状态模型、运行方式、打包策略或关键依赖关系变化时，至少评估 README / AGENTS 是否需要同步。
- 只有重要、长期且需要保存决策背景的变化才考虑 ADR，不为了流程机械创建文档。
