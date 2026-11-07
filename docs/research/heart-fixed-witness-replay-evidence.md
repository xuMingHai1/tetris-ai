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

## 本次新增修改：journal 写前累计额度

`FixedWitnessReplay.run` 现在强制调用方传入 `JournalQuota`，无无限额重载。
该合作式额度接受1..10MiB，必须由未来父监督在同一root中于启动前永久预留；
它不是额外10MiB，也不替代READY/verified和pipe额度，尚未接通历史诊断入口。
同一实例可用于多个journal，失败open/write/force不返还额度。
START先序列化UTF-8（含LF）并扣费，再CREATE_NEW；ATTEMPT、STEP和END
同样先完整扣费，再写入并force。额度不足抛出IOException，不能被INVALID处理器
吞掉或补写END；已force前缀由独立reader保持INCOMPLETE。
ATTEMPT不足时不执行下一次terminal replay；STEP额度不足时可能已经完成一次
真实replay但尚无STEP记录，该前缀绝不当成完整结果。END不足也不返回COMPLETE。

人工三步路径覆盖逐事件少一字节、精确全量、UTF-8多字节START、独占文件失败、
不存在父目录失败和同额度不返还。没有读取历史fixture、执行22步或新增population。
该额度只约束journal合作式writer；没有独立最终report文件writer或整体supervisor，
未来任何额外报告仍须共享父root或另有已实际执行的预留，不能凭该组件宣称
整个session合计已受限。PR170的HOME/TMP分类仍待确认，不选择其任何选项；
网络隔离、整体预算/认证、最终整体Review/CI、兼容性/inputhash和唯一登记门保持。
不修改冻结设计、生产/构建/资源/selector/guard；README/AGENTS/ADR无需同步。

## 执行证据的父端结构核验

本次新增修改：`scripts/fixed_witness_replay_execution_evidence.py` 实施已接受PR183的
独立保存证据边界，不提供进程启动、ACK、加载清单或动作执行入口。
`retain_execution_request` 比对调用者独立保留的Java规范声明原字节/hash和input原件，
保存不可变两文件快照，前后复核规范目录、精确集合、NOFOLLOW、有界字节与文件身份。
Python不生成权威规范JSON、不代替Java的Input/Request语法校验；调用者仍须排除
所有writer及硬链接别名。已有report-only三文件描述符与旧模式不变。

`reserve_execution` 在同一个父ByteQuota中一次永久预扣H+J+D+R，再分配四个子额度。
不足不产生部分扣费；失败/未使用不返还。父端任何新增副本、日志、receipt或失败记录
另从同root扣费，读不重复收费。该helper尚未接入启动器，不能声称已阻止真实JVM启动，
也不约束HOME/TMP/target的非合作writer，不改变原10MiB或170分类。

`verify_execution_artifacts` 必须由未来完成独立build/process认证的父端在退出后调用，
输入是ACK前保存的READY原字节、nonce、权威请求及四项额度。它只接受
ready.json、journal.jsonl、report-request.json、report.json、replayed.json，
复核每个文件摘要/长度、合计H和退出后的字节/身份稳定性。
它将执行后ReportRequest解析对象绑定到原input/paths/expected与实际journal摘要，
再核对report的requestIdentity。START必须等于原动作声明；ATTEMPT/STEP/END的
顺序、路径、计数和保存行与报告对应，END/report/replayed状态必须相同。
不重复推导几何或执行动作；Java Reader/Checker仍负责几何和终态事实。
返回REPLAY_REPORTED仅表示保存结构闭合，独立journal_state保留INVALID/MISMATCH，
diagnostic_authorized/provenance_authenticated/eligible_for_runtime_validation/
network_isolation_verified始终false。调用者传入READY不等于已完成独立认证。

人工测试严格占原16项中的13（原子不足）、15（退出journal字节漂移）、16（终态状态冲突），
没有参数化新增子例；mock声明及手写INVALID journal不是Java规范向量验证或真实动作结果。
现有FixedWitnessPipeTest桥接这三项Python检查；Windows跳过Linux桥接，因此还须单独运行Python。
请求组件已占1–3，已合并PR185的快照组件占4；其余5–12/14留给执行接线，不能重置预算。
本组件不完成PR183整体实现；下一步接入显式execution模式清单、唯一ACK后JVM动作入口、
父认证握手与run_process封闭成功态，并让真实Linux人工单JVM联调验证两侧协议。
完整网络/历史执行仍等待170/174/173实际能力、最终整体Review/CI及唯一登记；
原16进程/10MiB、#140/GoalWindow/#95/#96和reserved/provider/runtime/default边界保持。
