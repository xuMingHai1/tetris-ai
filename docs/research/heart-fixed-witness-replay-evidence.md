# 固定旧 witness：回放与逐步证据组件

本阶段接续 [#156 输入前置](heart-fixed-witness-replay-input.md)，实现已接受 [设计](../design/heart-fixed-witness-replay.md) 的同包 replay、逐步 journal 和独立结构检查。**这是 test-only 组件交付，不是完整诊断链路或历史回放结果。** 尚缺独立父监督、真实 clean-build/source/classpath/class认证、完整实现最终 Review/CI 和唯一 session 登记；不执行历史22步。

## 本次新增修改

- `FixedWitnessReplay.run`：严格 reader 先验证全部输入及所有路径的终态期望，然后每条路径从空20×10棋盘开始。每步只调用一次生产 `snapshotForSpawnedPiece` 和一次 `requireTerminalPlacement`，后继取真实 `resultingBoard`；共享 `BoardRules.canPlace` 显式拒绝初始重叠，不能让 HARD_DROP-only 计划逃离非法出生状态。没有 main、历史 resource 自动读取、generator、seed、preview、planner、候选枚举或搜索入口。
- `FixedWitnessReplay.Row`：记录原 path/step/source line/piece/actions/input hash，pre/post row-major 200位 occupancy、其 UTF-8字节 SHA-256 和 occupied count，生产消行与逐路径累计绝对消行，`ShapeTarget.progress`、生产 maxHeight/headroom 及 `ShapeWitnessConstraintAudit.classifyHoles` 的各 role holes。只调用该分类扫描，不调用会枚举候选的 audit。工厂创建/动作恢复依赖原生产实现（含不参与 occupancy 的随机颜色），不生成随机方块序列。
- 独占 JSONL journal：`CREATE_NEW`，START 后在每次唯一 replay 前写 ATTEMPT，事实产生后立即写 STEP，每次 FileChannel `force(true)`；正常完成、非法动作/输入或终态不符分别写 END COMPLETE / INVALID / EVIDENCE_MISMATCH。终态不符立即停止，不再执行下一路径。I/O/异常退出保留前缀，不伪造 END；外层监督必须把缺 END 解释为 INCOMPLETE。COMPLETE 只指组件的完整固定动作链，不认证构建、历史来源、诊断成功或 runtime 权限。
- `FixedWitnessReportChecker`：不执行动作，仅核对输入身份/顺序、bitstring/hash/count、路径重置与棋盘衔接、每步和累计 `occupied_after = occupied_before + 4 − 10×clearedLines`、holes 分区、height/headroom、角色计数边界及调用方提供的原日志终态字段。不会重建棋盘规则或 tile decomposition；结构检查不能验证所有报告指标真实，更不能认证旧构建。
- `FixedWitnessJournalReader` / `FixedWitnessReportCodec`：独立读取已保存 journal，使用调用方的固定输入和期望，不信任 START 自述；严格 JSON 字段/类型/重复 key/顺序/计数，拒绝 false COMPLETE、hash/守恒篡改、缺字段、END 后数据。最多10MiB、每行64Ki字符、最多46个正常事件；未换行尾行不消费并保持 INCOMPLETE。显式 Map/JsonNode wire 映射避免反射访问非导出的测试包，不改 module 或暴露生产 API。

原设计要求的每步 REQUIRED 缺口、FORBIDDEN 占用、non-FORBIDDEN holes、目标高度超出及绝对消行，可从保存字段直接呈现；它们只是几何必要条件，不组合为 Q 认证。父监督/最终结果归档仍须明确报告 clean、非Q和有无消行，不从 occupancy 推断颜色或 surviving tetromino 身份。

## 验证与来源刷新

只用人工两路径三行 CSV 和单步人工棋盘：首次gravity后碰撞、非法出生重叠、中间动作越界、正常落地/路径重置、真实消行与绝对计数、非零 hole 分区、hash/期望缺失、终态不一致立即停止、独占旧 journal、未完成/torn输出、字段/计数/hash/守恒篡改。没有打开历史fixture作 Java 测试，没有执行历史22步或新population。

实现基线 `b74eab1458a040c4c4a4c4c4ff5c6518a9c16eee` 相对前阶段兼容性基线 `c771bbc39369eecdcb7f1ce548bed151153701dc` 无生产/构建变化；当前28个相关生产源码 SHA-256 全部与原 `source-comparison.json` 的 implementation_base_sha256 一致。因此沿用其工厂初始方向/首次gravity/固定动作的有限静态兼容性结论，不反向认证历史执行。

Windows checkout 会把未固定 eol 的 CSV 检出为 CRLF，本地字节 hash 为旧 `6f30373d…`；原 Git blob 的规范 LF hash 仍应是 `1282206d…`。reader 不修复或放行换行差异；唯一诊断仍须 Linux 干净 checkout，父监督须验证实际输入字节。没有因此修改 fixture、hash或 `.gitattributes`。

本地 JDK27.0.1 / Wrapper3.9.16 标准 verify：359 tests、0 failures/errors、9 skipped，新增9项人工测试通过。跳过项不是执行成功，CI和独立最终Review另由 PR 记录。新文件不命中现行 benchmark-sensitive 模式，预期 benchmark=none；不修改 selector或原guard逃避验证。

## 剩余实施门

从阶段二最终合并后的最新 default branch 建新分支，独立实现 `scripts/fixed_witness_replay*` 及普通JVM入口、600s clean Wrapper build、120s JVM /180s父监督/900s整体限制、256m heap/128m metaspace、10MiB合计输出终止、timeout/partial反例、真实 class/source/argv/classpath/dependency身份和前后漂移/shadow检查。监督必须解析实际 journal，验证最后所有期望及完整22步，只把认证通过且 COMPLETE 的一次登记session视为完整结果。

最终整体实现必须重新独立Review及适用CI；登记精确SHA、原输入hash、兼容性、固定预算、命令/位置和唯一session后才执行一次。不得将人工测试当作该诊断，不自动重试、不复活 #140/GoalWindow、不更改 Q/冻结协议/guard，不解锁 #95/#96、reserved/provider或runtime。该组件不改变系统架构或生产运行方式，README/AGENTS/ADR 无需同步。
