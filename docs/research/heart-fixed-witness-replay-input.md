# 固定旧 witness 回放：输入与静态兼容性

本阶段实施已接受的 [固定动作设计](../design/heart-fixed-witness-replay.md) 的输入前置：静态 fixture、逐行来源清单、严格 reader 和源码兼容性。**尚无回放入口、父监督、构建认证、报告检查器或唯一执行登记；不允许运行完整历史路径。** 不是新的搜索设计，也不是诊断结果。

## 固定输入

- 规范来源：[run35857294015 / job107168719470](https://github.com/xuMingHai1/tetris-ai/actions/runs/35857294015)，源码 `e0f4a8bb997470dfaf1896234240c2eccbab1d63`。
- 实时取得 [#91 原转录](https://github.com/xuMingHai1/tetris-ai/issues/91#issuecomment-6576326754)，与已保留三个解码日志中的实际 timestamp-prefixed witness 行、三份提取 CSV 逐行一致：1001/12 步后 1008/10 步。没有重新获取历史 artifact 或重新认证历史构建。
- 新静态 [historical-witness.csv](../../src/test/resources/fixed-witness-replay/historical-witness.csv) 为 UTF-8/ASCII、LF、含 header 和末尾 LF，共 4,689 bytes；SHA-256 `1282206df0bebbc2ddf6efd3a9a631f9691edbd05f585eb782ec6a38e78377fc`。
- 旧提取 CSV 为 CRLF，原字节保留，SHA-256 `6f30373ddfe4c899b53e631404b745fe998c6e7ac9cb330ab193c01b2d0f4da1`。新 fixture 是按设计规定将相同行转录为 LF；没有覆盖旧证据，也没有改 piece/action、顺序或补行。日志中的 22 个数据行不含 header；header 采用设计规定、原提取文件和 issue 转录一致的 schema。
- [input-manifest.json](../../src/test/resources/fixed-witness-replay/input-manifest.json) 固定每行 source line/path/step/piece/action count、来源 run/job/SHA、原解码日志 hash 和新输入 hash。1001 的 action counts 为 `21,17,18,23,18,16,15,20,18,11,17,15`；1008 为 `22,18,16,15,22,21,21,14,17,20`。均以唯一 HARD_DROP 结束。

这只是既有动作的来源转录，没有实例化 piece、生成 seed/bag、执行动作或评估棋盘。历史数字是路径标签，不能交给 PieceGenerator。新 fixture 的 byte hash 只适用于规定的 LF 字节；Windows checkout 的自动换行不能通过 reader 被静默修复。唯一诊断仍要求 Linux 干净 checkout。

## 源码兼容性结论

设计基线 `83eb41169098cd214c49a3bc7c199e8113f82749` → 实施基线 `c771bbc39369eecdcb7f1ce548bed151153701dc` 仅为 #155 两份设计文档，无生产变化。历史 #31 → 实施基线从指定 commit 的公开 GitHub codeload 源码静态比较；[source-comparison.json](../../src/test/resources/fixed-witness-replay/source-comparison.json) 登记相关 28 文件的前后 SHA-256。源码一致性不能认证旧 classpath、依赖或历史执行。

| 路径 | 实际变化与对固定输入的影响 |
| --- | --- |
| `TetrisFactory`、七种 `*Block`、`AbstractBlock`、`Cell`、`BoardPosition`、`BoardRules` | 字节一致；工厂坐标、移动、旋转变换、碰撞和消行没有变化。 |
| `Tetris`、`Abstract2StateBlock`、`Abstract4StateBlock` | 仅增加 `rotationState()` 访问器；初始方向和变换不变。 |
| `GameSnapshot` / `ActionStateSearch.replay` | 新增方向字段及 `restoreCurrentPiece()`。旧 replay 创建工厂实例后安装捕获坐标；新实现先按 currentRotation 旋转，再安装坐标。固定路径每步均调用 `snapshotForSpawnedPiece`，构造器令方向为 0，因此恢复循环执行 0 次，与旧逻辑相同；并非断言任意 partially-played snapshot 等价。 |
| `BoardSimulator.snapshotForSpawnedPiece` | 方法不变：工厂创建、一次 `downMove()`、捕获有序 cells，不补 preview。其他 `rotatedCells` 的方向恢复变更不在固定 terminal replay 调用链。 |
| `ActionPlanSimulator.requireTerminalPlacement` | 方法不变；只复用上述 replay 与 BoardRules。变化仅在独立的 `hasReachableTerminalPlacement`/`hasVisibleLanding` 快路径；新诊断禁止调用这些枚举/探测入口。 |
| `AiAction`、`AiPlan`、`AiPlanValidator`、`PlacementCandidate`、`ShapeProgress` | 字节一致。 |
| `ShapeTarget` | 仅增加 `requiredLocalCells()`，mask、锚定、`progress` 和 `roleAtBoardCell` 未变。 |
| `BoardOccupancyMetrics` | 增加 maxColumnHeight/headroom 同一列扫描统计；原 holes/holeCells 扫描与分类定义不变。 |
| `ShapeWitnessConstraintAudit` | 整文件字节一致。只允许调用其 package-local `classifyHoles`；禁止调用 audit，后者会枚举候选并运行 planner。 |

结论：**在每条路径空 20×10 棋盘开始、每步工厂初始方向、相同有序 cells、首次 gravity 后只重放原 actions 的前提下，静态兼容性成立。** 历史 audit 本身也是每步 `snapshotForSpawnedPiece(board, witnessStep.pieceType())` 并从空棋盘开始。未来实现仍必须验证合法动作、守恒与原终态指标；不一致就 EVIDENCE_MISMATCH 停止，不能据此修改输入。生产源码后续变化须重新核对这一有限结论。

## Reader 与验证边界

`FixedWitnessInput.read` 先比对原 byte SHA-256，再校验 LF/header/末尾 LF、路径顺序与连续步号、完整数量、严格 piece/action 名及 1–64 actions，复用 `AiPlanValidator` 拒绝提前 HARD_DROP，再要求末动作 HARD_DROP。输入最多 64 KiB、最多两条路径和 22 placements；这些是解析防御上限，不扩大冻结执行预算。结果为不可变列表，保留 CSV 行号。

reader 没有读取 resource 的静态初始化、main、piece factory、棋盘、搜索、replay 或 provider 调用。CI 的 `FixedWitnessInputTest` 仅用人工 3 行 CSV 验证完整性/篡改/截断/重复/顺序/换行/非法动作/超限失败边界；不打开或解析完整历史 fixture，更不会执行它。静态 fixture 是经过来源核对的研究输入，不是生成的诊断输出。

本地 JDK 27.0.1 / Wrapper 3.9.16：6 项新人工 reader 测试通过；标准 `verify` 为 350 项、0 failures/errors、9 skipped（包括 Linux 专属监督前置），不把 skip 计执行成功。远端最终 SHA 的适用 CI 和独立完整 diff Review 另由 PR 记录。

## 接续门

本阶段不改变架构、生产运行方式或 selector；新 `FixedWitnessInput*`/fixture/本文不命中现行 benchmark-sensitive 模式，预期 benchmark=none，仍需标准质量 CI。未修改 selector 以改变路由，也没有扩大 frozen guard。

后续在最新主干完成同包逐步 replay/report、人工棋盘行为反例、独立父监督（build600s/JVM120s/parent180s/total900s、256/128MiB、10MiB输出）、真实 source/classpath/class认证和独立报告检查器。最终完整实现独立 Review、适用 CI、兼容性刷新和唯一执行登记前，不执行22步；本阶段合并不授予采集许可。#140/GoalWindow 停止，Q/原guard/冻结协议/#95/#96及runtime/default权限不变。
