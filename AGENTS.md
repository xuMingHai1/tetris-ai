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
- `xyz.xuminghai.tetris.ai`：headless 决策边界。只消费不可变 `GameSnapshot`，模拟候选落点并输出 `AiMove`；不得依赖 JavaFX Property、Animation、Audio、Robot 或 View。`MoveCandidateGenerator` 是 heuristic 与远程 agent 共用的合法候选来源。当前包含本地 `HeuristicTetrisAgent` 和异步 `ai.jev.JevTetrisAgent`，不是通用 Agent/Plugin 框架。
- `xyz.xuminghai.tetris.integration.typesafe`：TypeSafe System One HTTP integration。使用 JDK `HttpClient` + Jackson，API key 只从 `TYPESAFE_API_KEY` 获取；不得记录 Authorization header 或把密钥写入仓库。429/529 使用指数退避，网络调用不得阻塞 JavaFX thread。
- `xyz.xuminghai.tetris.game`：游戏规则和运行状态，包括网格、计分、等级、时间线、输入动作和动画协作。规则变化应优先在这里表达，不把规则复制到 View。
- `xyz.xuminghai.tetris.view`：JavaFX 展示层。负责观察状态并渲染，不应成为游戏规则的第二事实来源。
- `xyz.xuminghai.tetris.util`：音频和版本等辅助能力。不要把业务规则沉淀为通用 util。
- `TetrisApplication`：JavaFX 应用启动和顶层输入装配。保持 bootstrap 职责，不把核心游戏算法堆积到入口类。
- `src/main/resources`：CSS、图片、国际化、音频等运行资源。WAV/MIDI/图片按二进制资源处理。

AI 已通过 `GameSnapshot -> TetrisAgent/AsyncTetrisAgent -> AiMove` 建立最小边界。JavaFX runtime 使用 `MANUAL / HEURISTIC / JEV` 三种模式；Jev pending 时只冻结当前 piece，不阻塞 FX thread，完成后必须回 FX thread，并校验 decision generation 防止迟到响应应用到错误方块。远程失败使用显式 heuristic fallback。新增搜索深度、look-ahead 或其他算法时应复用现有状态/动作契约，不把 JavaFX runtime 引入搜索路径，也不提前建立通用 Agent/Plugin/DSL 框架。

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

Any change that alters architectural understanding must also evaluate its documentation impact.

判断标准：是否会改变后来者对系统结构、职责、依赖关系、运行方式或关键设计决策的理解？

- 普通 bugfix / 小型测试补充通常无需额外 architecture 文档。
- 包职责、AI 边界、游戏状态模型、运行方式、打包策略或关键依赖关系变化时，至少评估 README / AGENTS 是否需要同步。
- 只有重要、长期且需要保存决策背景的变化才考虑 ADR，不为了流程机械创建文档。
