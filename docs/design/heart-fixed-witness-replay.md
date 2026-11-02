# 固定既有 HEART witness 的动作回放与结构核验设计

状态：设计候选；仅当本设计最终 SHA 独立 Review 无阻断、适用 CI 成功并正常合并后接受。实现须另经完整最终 Review/CI，再登记并执行一次诊断。本 PR 不含实现或诊断结果。总任务 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。

## 问题与证据

终态证书唯一实测 [run41429862429](https://github.com/xuMingHai1/tetris-ai/actions/runs/41429862429) 已按 [结果](../research/heart-terminal-certificate-result.md) 停止：固定 2,000 枚举耗尽，0 完整 order/Q 证书。这既不证明 HEART 无解，也不授权继续该搜索。

[#91 证据断点](https://github.com/xuMingHai1/tetris-ai/issues/91#issuecomment-6576326754) 恢复了三个历史 job 日志中的相同 22 步动作：

| run / job | 源码 head | 内容 |
| --- | --- | --- |
| 35848129668 / 107139126340 | 9380a16441b0e05febc5a6904568710987eaf39b | witness |
| 35850242047 / 107145942987 | c297f70b3fa30286efa844c2dc61ff66082a94d8 | witness / audit |
| 35857294015 / 107168719470 | e0f4a8bb997470dfaf1896234240c2eccbab1d63 | witness / hole audit |

各 artifact 列表为空；仍有日志不等于仍有原 ZIP 或历史构建认证。以最后一个 job 为规范输入、另外两个仅作只读交叉核验；原 CSV 动作已保存在上述 issue 评论。1001 的 12 步、1008 的 10 步是历史路径标识，不能据此重新生成 seed/bag、执行 feasibility search 或 continuation。

日志记录两终态均 32 REQUIRED / 0 FORBIDDEN；non-FORBIDDEN holes 分别 21、20，故不是原 Q。audit 的 cleared_lines_delta 是相对 SURVIVAL baseline 的差，不能作为绝对消行数。完整逐步 post-clear 棋盘、绝对消行、中间几何义务仍未知。新问题是恢复这两条既有路径的结构，不是寻找新的成功路径或挽救 #140。

## 备选方案与接受决定

| 路径 | 收益 | 局限 / 决定 |
| --- | --- | --- |
| 仅保留日志 | 零执行，已有终态非 Q 结论充分 | 无法获得绝对消行与逐步 occupancy，保留为退路 |
| 固定动作生产回放 | 最多 22 次 terminal replay，输出逐步真实棋盘与消行 | 推荐；只能证明固定输入在指定当前构建上的行为 |
| 重跑旧 feasibility / 新域搜索 / 提高终态预算 | 可能找到不同路径 | 排除；不是必要补证，会触及已停止假设或新增 population |

在既有 test-only、离线、无费用授权内选择第二条。这里的决定是提交接受的方案，不越过设计 Review/合并和实现 Review/登记两个门。若来源或兼容性不足，退回第一条，不自动改动作、换版本执行或扩大研究。

## 固定输入与来源边界

1. 规范输入仅取上述 #31 原 job 的 witness CSV，按 1001 step1–12，然后 1008 step1–10；每条路径各从空 20×10 棋盘开始。逐行 piece type 和 actions 原样使用，不实例化 PieceGenerator，不取随机数，不读取未来 bag，不补 preview。两路径不连成一局。
2. 实现阶段把这 22 行作为有来源的静态测试输入提交；固定 UTF-8/LF、完整 header、末尾 LF，并登记精确 SHA-256、每条行号、piece/action 数、原 run/job/head 及 issue 来源。先对三份已恢复输入逐行核对。不得从生成文件反推或修补原动作。输入和 hash 变更须重新设计审查。
3. 已保存的 #31 connector 解码日志 SHA-256 为 fd1931913dc45f499bf3b466df5e4f4dfa7e86fdc1504295ce61b6392feeb564。它只标识已保存解码文本，不是下载 ZIP hash。原日志到期/不可重新读取时，明确使用 issue 中已恢复的转录及其局限，不宣称历史字节已重新认证；缺行、歧义或交叉核验不一致则停止。
4. 设计源码基线为 main@83eb41169098cd214c49a3bc7c199e8113f82749。实现从届时最新 default branch 开始，登记设计基线→实现基线及历史 #31→实现基线的相关生产 diff。不能假设历史与当前等价：已观察到 snapshot orientation restoration 与 landing probe 的演进。
5. 必须静态核对 piece factory/旋转、BoardRules、spawn 首次 gravity、ActionStateSearch.replay、ActionPlanSimulator.requireTerminalPlacement、ShapeTarget 和 hole 分类的相关变更。说明为什么原初始方向路径仍可作当前构建输入；不能证明兼容时，在执行前记录 COMPATIBILITY_UNRESOLVED 并停止，不切历史版本多跑一臂。即使回放成功，也不反向认证旧 run 的 classpath、依赖或原棋盘。

## 实现范围和复用

只新增正常同包 src/test/java/xyz/xuminghai/tetris/ai 下的固定输入 reader、回放入口及必要行为测试；静态 fixture 可放 src/test/resources。若需父进程，在独立 scripts/fixed_witness_replay* 下实现；不得复用受冻结协议约束的 GoalWindow/TerminalCertificate session 作为新诊断，也不得改变它们的 runner、协议或 guard。

每步复用 BoardSimulator.snapshotForSpawnedPiece 的真实 post-spawn gravity 边界，使用原 piece type 和 AiPlan；仅调用一次 ActionPlanSimulator.requireTerminalPlacement，后继棋盘取 PlacementCandidate.resultingBoard。它复用 ActionStateSearch.replay 与 BoardRules。禁止调用 landings/ranked/candidates/search/hasReachableTerminalPlacement、原 audit、runtime planner、viability 或 continuation；这些会隐式枚举。这里的“零枚举”是零候选状态搜索，动作回放内的碰撞、hard drop 与行扫描不算候选搜索。

指标复用 ShapeTarget.progress、BoardOccupancyMetrics 与既有 hole-role 分类（同包可访问），不复制游戏规则。不得修改 src/main、资源、构建输入、module、生产 API、AI factory、默认行为、通用 safety、Q、原 guard、冻结协议或 #105 分析器。新脚本命名及文件列表须检查现行 selector；不修改 selector 逃避既有验证。实现 PR 只做必要 synthetic 测试，不在 CI 自动执行完整 22 步历史诊断。

## 输出与解释

每步保存 path ID、step、piece、原动作及输入 hash、pre/post occupancy 的固定 row-major bitstring 与 hash、生产 clearedLines、累计绝对消行、ShapeProgress、headroom/max height、raw/各 role holes。字段不存在则报错，不填 0。每次 terminal replay 后立即刷新 journal；最终输出 COMPLETE 或明确失败前缀。保留原 stdout/stderr、环境、配置、命令、class/source hash 和报告。

独立报告检查器只能校验结构/计数/hash/守恒，不执行第二遍动作。两条路径分别要求步号连续、棋盘衔接、holes 各 role 求和等于 raw、最终 REQUIRED/FORBIDDEN 和原日志相符；原 #31 最终 raw/forbidden/support/outside holes 应分别为 34/13/13/8 与 33/13/14/6。比较不一致时标记 EVIDENCE_MISMATCH，保留当前真实输出，不能改动作直到吻合。

以初始 occupancy=0 检查每步 occupied_after = occupied_before + 4 − 10×clearedLines；累计同理。只作独立数据一致性检查，不代替生产消行逻辑。逐步呈现 REQUIRED 缺口、FORBIDDEN 占用、non-FORBIDDEN holes、目标高度超出与绝对消行；报告哪些步骤不满足原几何域各项必要条件，不把必要条件组合冒充完整 Q 认证。终态 clean、非 Q、无消行与否分别报告。

不追踪或重建 settled tile 身份、颜色或 surviving tetromino decomposition；消行会删除/移动格子，occupancy 不足以推断原几何证书。该设计不计算 rank/risk/budget 新阈值，不建立在线 eligibility，不估计总体成功率、survival 或实时延迟。

## 冻结预算与停止规则

| 资源 | 上限 / 规则 |
| --- | --- |
| 输入 | 恰好 2 条、22 placements；每步 1–64 actions，恰好最后一个 HARD_DROP，前缀不得含 HARD_DROP |
| 枚举/搜索/生成 | 0；禁止新 seed/population、bag 生成、solver、warmup、候选/preview/后续搜索 |
| 实际回放 | 全诊断最多 22 次 requireTerminalPlacement；顺序固定，失败立即停止，不跳过失败路径 |
| 内存 | 单直接 JVM -Xmx256m / -XX:MaxMetaspaceSize=128m；记录实参与可用 RSS，未观测 RSS 不得宣称硬 RSS 上限 |
| 墙钟 | clean Wrapper build 最多 600秒；单诊断 JVM 120秒；含启动/认证/输出的父监督 180秒；整个命令最多 900秒 |
| 输出 | 父监督合计输出最多 10MiB；超限立即终止，保留前缀与计数，标记 INCOMPLETE |
| 执行次数 | 最终实现登记后只运行一次；任何 negative/error/timeout 都不自动 retry、Re-run 或换环境执行 |

注册记录精确实现 SHA、输入 hash、静态兼容性结论、预算、命令、执行位置和唯一 session ID；确认无既有 session 才启动。Linux 普通 JVM、JDK27、仓库 Wrapper 3.9.16 clean test-compile；保存真实 JDK/argv/classpath、依赖与相关加载 class hash、源码与输入 hash，检查构建前后没有漂移或同 FQN shadow。测试入口不能带 JavaFX Toolkit，不访问 provider、网络或凭据。构建可下载已有依赖，诊断 JVM 禁止网络。

超时/OOM/输出超限/非法动作/输入或来源错误/棋盘与日志不符，分别保留具体原因，整体为 INCOMPLETE/INVALID/EVIDENCE_MISMATCH，不能称 BOUNDED_MISS 或无解。成功仅指 22 步输入完整合法、认证及报告一致，并得到这两条路径的结构事实。若没有消行或没有新机制，仍如实完成分析，不追加路径、改动作或再搜。

测试与唯一诊断分开：实现 CI 只运行小型人工 fixture，覆盖非法动作/中间碰撞、初始gravity、生产消行、行数守恒、缺失/重复行、错误hash、timeout/partial输出等必要失败边界；不得用完整历史路径作 synthetic fixture 或偷偷执行旧 population。

## 分阶段门与后续

- 设计最终 Review 检查来源、替代方案、范围、预算和停止条件，适用 CI 成功后正常合并；此时只接受实现范围。
- 实现最终 Review 检查完整 diff、零候选枚举调用链、真实生产复用、来源认证、失败前缀与父监督，并通过适用 Wrapper/CI。登记唯一执行后才回放；preflight 不使用22步历史输入。
- 结果另经独立核验归档，包含原证据与新构建结果的明确分离、所有停止原因及未知。成功不授权下一 population 或 runtime。
- 后续只依已有结构证据比较实质不同的新设计；不得把本回放换名为 #140 继续搜索，不复活 GoalWindow。#95/#96、reserved/provider、生产/default adoption 均未解锁。

本设计不改变系统架构或运行方式；研究索引增加导航即可，不修改 README/AGENTS/ADR。
