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
- `xyz.xuminghai.tetris.ai.benchmark`：headless evaluation 工具。使用 seeded `BagPieceGenerator` 和生产 `BoardSimulator` / `PlacementCandidate` 推进状态；新方块在生成 AI snapshot 前必须先执行与 `GameWorld` 相同的一次初始自动 `downMove()`，保证候选可达性从相同坐标边界开始。记录 gameplay outcome、decision latency、fallback、board health 和 objective telemetry。BUILD_SHAPE 还记录逐局最佳 visual error / zero-forbidden required coverage / full-required forbidden intrusion；独立 `ShapeFeasibilityApplication` 可对固定 7-bag 序列执行 bounded construction search，并在找到 clean completion 时输出真实 `AiPlan` witness。clean witness 还会由 `ShapeWitnessConstraintAudit` 逐步对照 runtime top-5、Risk Controller、Safety Budget 与 greedy selection，以定位已知成功路径的第一阻断层。telemetry、feasibility search 和 audit 只用于评估，不能反向影响 gameplay decision。benchmark 不启动 JavaFX runtime，也不复制游戏规则或重新计算这些指标。
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
- `BUILD_SHAPE` 等 creative objective 必须基于生产 `resultingBoard` 的 post-row-clear 事实评分，不能维护第二套棋盘；当前 board state 只有 occupancy 时不得推断或伪造 settled-piece color，颜色目标要等显式 color-aware state model。Creative target 必须显式区分视觉 REQUIRED、视觉 FORBIDDEN 与允许的物理 SUPPORT，不能把“目标格都被占用”误当成完整图形；clean completion 还必须保证 FORBIDDEN 为空。BUILD_SHAPE 保留 heuristic top-5 作为当前 runtime 的 survival-quality prior，同时记录全量 action-native reachable 数量用于观测；SURVIVAL top-1 始终是风险 baseline，任何非 baseline 候选都必须通过相对 SURVIVAL top-1 的 `ObjectiveSafetyBudget`。不要直接扩大 runtime 到 full reachable envelope；AI Benchmark #30 已证明 top-5 是 known clean construction path 的真实 blocker，#48 又证明这些 witness state 具有显著 continuation viability，因此允许在独立 benchmark agent 中做 full-reachable + explicit guard 对照，但 runtime adoption 仍必须同时满足 construction improvement 与 survival regression gate。一步 preview lookahead 已通过 20×500 benchmark 验证但未通过采用门槛；不要继续盲目增加 lookahead 深度。AI Benchmark #29 已找到两个真实 clean HEART witness（seed 1001/12 pieces、1008/10 pieces），因此 HEART 可构造性不再是当前问题。AI Benchmark #30 进一步确认这两个 witness 的 22 步中有 19 步在 top-5 外、17 步进入 DANGER、22 步全部被 raw Safety Budget 拒绝，因此不能只放宽单一阈值。AI Benchmark #31 的 target-aware hole audit 又确认：虽然 FORBIDDEN / SUPPORT_ALLOWED 占了绝大多数 raw hole observations，但只排除 FORBIDDEN 后仍 0/22 通过，说明 hole 不是唯一 blocker。construction safety envelope 已记录 clean witness 的绝对 headroom、height、hole composition、bumpiness、survival rank、shape progress，以及当前/下一 piece reachable outcomes。viability calibration 已在相同 deterministic future piece suffix 下确认：22/22 known clean witness state 走满 depth-4 bounded continuation，20/22 被生产 SURVIVAL top-1 接管后继续完整 24 块，其余仍继续 21/20 块，21/22 不弱于同一步 SURVIVAL baseline。该证据允许 benchmark-only `ConstructionSafetyGuard` 实验放开 full reachable current candidates，但不能直接改变 runtime。第一轮 guard 已只使用已知 preview piece 的真实 action-native reachable outcome capacity，并比较 preserve-baseline / retain-half / any-continuation 三档。10×250 结果全部未通过 survival gate：runtime BUILD_SHAPE 10/10 到达 250，三档分别只有 3/10、2/10、2/10；虽然最佳 visual error 从 10 改善到 4~6，但 clean completion 仍为 0。因此 one-piece reachable-count guard 已被否决为 runtime safety model；不要通过继续微调 50%/100% 比例来挽救该设计。下一阶段 `RecoveryRobustnessBenchmark` 必须先做区分能力验证：正样本使用 known clean witness，负样本使用失败 `preserve-baseline` guard 局在 game-over 前最后 5 个 selected state；先让已知 preview 走 SURVIVAL top-1 recovery，再对 I/J/L/O/S/T/Z 七种未知下一块扫描 action-native reachability 与 recovery 后 board health。首轮 calibration 已观察到：22 个 clean witness 的 recovery headroom / worst-case post-unknown headroom 最低均为 8，而 35 个 failed-tail 样本最高均为 2（或不可恢复），并且距离 game-over 4 个成功 placement 时仍保持这一分离；但 min unknown reachable 在 9 有重叠，recovery holes 也有明显重叠。这个结果只证明 recovery 后的垂直余量值得继续研究，不是 `8` 或 `2` 的 runtime 阈值。下一阶段 `RecoveryReserveLeadTimeApplication` 正是该 calibration：失败 guard 最多回看 game-over 前 30 个成功 placement；healthy control 只能来自当前 runtime BUILD_SHAPE 确实到达完整 piece limit 的局，并按每 5 个 decision + 最后 30 个 decision 采样。cutoff 2/4/6/8 只用于同时报告 clean/control false positive 与失败预警 lead-time，不得因为某个 cutoff 在 10 个 seed 上表现好就直接写进 runtime。首轮 10×250 lead-time calibration 已得到：`recovery-headroom<=2` 对 clean witness 0 warning、healthy control 4/740 sample warning（只出现在 seed 1003 连续 4 步），7/7 失败局均被捕捉，observed lead-time 最少 6、平均 16.3、最多 29 步；cutoff 4/6/8 的 healthy false positive 明显变差。低-reserve 条件下 healthy control 的 recovery holes 为 10~11，而失败状态为 15~60，但这仍是同一 seed 集上的条件分布，不得直接写成 `holes>=15` 或任何 composite runtime guard。`RecoveryReserveValidationApplication` 承担下一步 out-of-sample 验证：固定使用独立 validation seed（自动 PR benchmark 为 2000–2019），healthy runtime 拉长到 1000 pieces，失败 preserve-baseline horizon 固定 500 pieces，并冻结组合信号为“preview 不可恢复直接 warning；否则 `recoveryHeadroom<=2 && recoveryHoles>=15`”。验证阶段不得 sweep / 调整这两个阈值；known clean witness 只作为回归控制，不属于 validation population。只有该固定信号在独立数据上同时保持低 healthy false positive、失败检测覆盖与可用 lead-time，后续 PR 才值得把它设计成 candidate runtime guard；仍需一次只改变一个主要变量。任何新的 BUILD_SHAPE safety policy 都必须和现有 SURVIVAL/TUCK_HUNTER 的通用 `ObjectiveSafetyBudget` 分开评估，避免用 creative semantics 污染通用 survival boundary；不得从 feasibility beam search 直接复制一个 runtime planner。benchmark-only feasibility search 可以遍历 full action-native reachable outcomes，因为它回答可达性问题；bounded miss 仍不得宣称 target 不可能。目标机会不存在或超出 budget 时 runtime 必须回退到 SURVIVAL。`ObjectiveRiskController` 只能根据 SURVIVAL top-1 的结果棋盘选择风险档位，不能根据待评估 objective candidate 反向改变 budget。runtime 可在 STRICT / CONSERVATIVE / BALANCED 间切换，但必须保持 `additional holes == 0`；RISKY 仍只用于 benchmark calibration。BUILD_SHAPE 在 DANGER 下必须停止 creative deviation并返回 SURVIVAL top-1。
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

`.github/workflows/ai-benchmark.yml` 同时作为手动 `workflow_dispatch` 和 reusable workflow，实际 benchmark 命令、参数校验与 artifact 收集只能维护这一份。`.github/workflows/ai-benchmark-pr.yml` 负责同仓库 PR 的自动选择：out-of-sample recovery-reserve 验证改动优先 `recovery-reserve-validation (20 × healthy 1000 / failure 500, seed 2000)`，recovery-reserve lead-time 校准改动使用 `recovery-reserve-lead-time (10 × 250)`，recovery-robustness 校准改动使用 `recovery-robustness (10 × 250)`，construction-guard 实验改动使用 `compare-shape-guard (10 × 250)`，其它 BUILD_SHAPE / shape 改动使用 `shape-feasibility (20 seeds, depth 24, beam 128)`，objective risk/safety 改动使用 `compare-adaptive (10 × 250)`，action-native reachability / shared board facts 改动使用 `action-provenance (10 × 250)`；一个 PR revision 最多选择一个 deterministic benchmark，优先级为 recovery-reserve-validation > recovery-reserve > recovery > guard > shape > objective > action，新的 push 必须取消旧 run。自动 PR benchmark 只允许目标为当前 default branch 的 same-repository PR，外部 fork 不得占用 self-hosted runner；自动路径不得调用 Jev 或读取 provider secret。手动 workflow 继续保留完整模式；`shape-feasibility` 使用独立的 `feasibility_depth` 与 `feasibility_beam_width`，不要把普通 gameplay 的 `max_pieces=500` 直接当成 feasibility depth。BUILD_SHAPE artifact 应保留逐 decision、逐 game construction diagnostics，以及 feasibility result/witness 和 clean-witness runtime constraint audit（若存在）；constraint audit 还应保留 target-aware hole breakdown 与明确标注为 counterfactual 的 forbidden-hole-exclusion 字段；clean witness 还应输出 `construction-safety-envelope.csv` 与 `construction-viability.csv`；recovery robustness 校准输出 `recovery-robustness.csv`，lead-time calibration 另输出 `recovery-reserve.csv`，out-of-sample validation 输出 `recovery-reserve-validation.csv`。这些 min/max、rollout/search horizon 与 class-separation facts 都只代表 observed evidence。PR 自动 shape-feasibility 固定 viability search depth 4 / beam 32 / greedy depth 24，以限制 self-hosted runner 成本；更深校准使用手动 workflow。Jev benchmark（含手动 `compare` 中的 Jev 部分）只有显式确认外部调用成本并存在 `TYPESAFE_API_KEY` repository secret 时才能运行，且单次最多 200 个潜在 Jev decision。benchmark 结果应上传 artifact，不提交生成结果到源码仓库。

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
