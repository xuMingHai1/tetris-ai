# HEART 下一构造方向：终态分解与落子顺序证书

关联 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)，承接 [GoalWindow 停止结果](../research/heart-goal-window-result.md)与[机理复核](../research/heart-goal-window-mechanism.md)。

**接受范围：2026-10-27 按用户连续研究授权，接受一次受限 test-only 离线诊断。** 核对设计基线 `dc98424d808b6da462c0fd1bdb9a56720c4bc8a8`；Codex 依据本稿的替代方案、固定投入和停止条件选择该研究问题。设计最终独立 Review、适用 CI 通过并合并后，允许另行实施；实现最终 revision 的独立 Review/适用 CI 通过后才执行一次登记诊断。本稿不表示工具已实现或诊断已执行。GoalWindow 保持否决，不重跑、不改 Q/rank/seed/budget；#95/#96、reserved/provider/runtime/default adoption 全部保持停止。

## 问题与已有证据

GoalWindow 在 greedy 轨迹附近截获当前或一步 preview 的精确终局，不建立从空棋盘通向终局的建设路径。固定80配对中全部68,706决策退回原 baseline；在已搜索范围没有 Q，不等于全域没有 clean。现有人工棋盘 fixture 证明局部终局分支可执行，不能证明该窗口会自然出现。

旧[两块债务事务](heart-online-construction.md)缺少中间损伤上限和跨事务存活依据；旧[每步几何域](heart-prefix-domain.md)把额外 hole 限制施加于所有前缀，既有20次 bounded miss 没有域内 witness，原实现还违反冻结目录边界。不能通过改数值或重跑这些候选解决建设问题。

下一步应先回答一个更窄、尚未知的问题：**是否存在符合7-bag前缀、无需消行、由完整四格方块拼成的低层 clean HEART，并能给出从空棋盘开始的真实生产动作证书？** 这是一种新的受限构造假设，不是对所有 HEART 路径的必要条件，也不是在线安全结论。

## 替代方向比较

| 方向 | 可能解决什么 | 主要缺口 / 决定 |
| --- | --- | --- |
| 扩 GoalWindow Q、rank、preview 深度 | 可能增加窗口 | 属于挽救已停止候选；不选 |
| 提前按 visual error 或 coverage 介入 | 比精确终局更早改变轨迹 | 已有 full-required 仍有大量 forbidden；局部收益不提供中间安全依据，不选为当前实现 |
| 重跑每步域 beam 或扩大深度 | 再找一条完整路径 | 复用已停止方向、未来序列和预算依赖；不选 |
| 终态分解 + 真实落子顺序证书 | 把“目标能画出来”拆成“方块能拼、顺序合法、来源可核验” | 推荐先做受限离线诊断；仍不保证随机序列机会或在线可用性 |

推荐理由是先取得可解释的建设结构，避免再次为不存在于 baseline 附近的窗口付出整局测量成本。终态分解不使用样本的胜负、seed或完整未来序列拟合在线规则；即使找到证书，也须另行设计只读当前/唯一preview的在线策略及独立安全边界。

## 新假设的精确定义

诊断只考虑20×10棋盘底部8行，沿用生产 `ShapeTarget.HEART` 的锚点和角色：32 REQUIRED 必占，16 FORBIDDEN 必空，16 SUPPORT_ALLOWED 与左右两列16 OUTSIDE_TARGET 格可选。底部80格中最多64格可占；终态需满足原 Q（clean、高度≤8、非FORBIDDEN holes均0）。Q在这里仅定义研究域，不修改已停止GoalWindow，也不把它当成v1.1完成定义。

一个终态由8至16个互不重叠的tetromino完整四格集合组成；可选格不要求全覆盖。额外拒绝满行。**只研究没有消行的构造族**：消行会拆散早先方块，终态未必仍可分解为完整四格，故本方法的 miss 不能排除有消行的 clean 路径。

分解候选从生产 `TetrisFactory`/core旋转生成四格形状后平移、去重，不手抄旋转表。这个静态集合求解器只提出几何分解，不判碰撞、落地、旋转合法性或物理支撑；所有转移合法性交由生产 `ActionPlanCandidates` 和 `ActionPlanSimulator`。不能用“上方无占用”的自写规则替代 tuck/旋转路径。

对每个分解，从空棋盘构建前向顺序：当前未用tile按type枚举顺序、坐标字典序遍历；该type须尚未用在当前7-bag组内。每完整7个type恰好一次I/J/L/O/S/T/Z，末组不重复。它只证明一个抽象7-bag兼容的有限type串，不证明某个seed会产生该串，也不计算出现概率或在线承诺。词序是诊断输出，不进入任何运行时planner输入。

每条边从当前真实board以生产spawn helper建立该type的初始自动down后的snapshot，完整枚举合法落点；只接受与tile新增四格及预期union完全一致、`clearedLines=0`的落点，并生产replay再次比对。每次都保留真实动作和完整post-clear棋盘。前缀可以有REQUIRED/SUPPORT/OUTSIDE holes，不要求旧域D；终态才要求Q。前缀无FORBIDDEN占用、不会高于8，仅因为它们是该无消行终态的子集，**不赋予这些前缀存活安全权限**。

找到第一条完整可重放证书即停止，不按长度、历史witness、seed或收益挑优。分解与顺序搜索都必须有明确计数和截断原因；有限投入未找到只记 `BOUNDED_MISS`，超时/错误记 `INCOMPLETE/ERROR`，不能写无解。

## 已接受的实现范围与冻结边界

从设计合并后的最新 default branch 新建独立诊断任务，不重开#111或把它计作#94的继续采集：

- `src/test/java/xyz/xuminghai/tetris/ai/TerminalCertificate*.java`：同包test-only几何分解、顺序诊断、生产replay及人工反例；使用正常包路径，不shadow/reflection，不修改生产API。
- 专属 `scripts/*terminal_certificate*.py`：监督、输入/输出与证书核验；复用已交付build/provenance的可复用部分，不能假称GoalWindow固定人口driver已经支持新诊断。
- 设计接受后单独Review实际接线方案。现有selector不会自动识别新文件；不得假定已有CI已执行新工具，也不得改路由绕开原frozen guard。

`src/main`、资源、pom/wrapper、HEART v1.1/#105分析器和原guard不改。生产 `ActionPlanCandidates`/spawn/role API是package-private，正常test同包访问；不重演#112在非benchmark生产目录加诊断导致guard失败的问题。实际实现PR必须先证明干净Wrapper构建及普通classpath同包调用成立；若不成立，停止，不临时扩大白名单。

本设计不批准采集population，不运行旧GoalWindow warmup，也不使用20000–20079、30000–30199或31000–31002作为新搜索输入。没有真实seed采集或远程模型成本。新workflow若确有需要，先独立审查其权限/触发/产物，不能在本稿中承诺可执行。

## 固定投入与证据

拟议一次诊断的共同预算：几何递归展开最多200,000节点、完整分解最多128个；全部分解共享最多2,000次生产枚举调用，终态最多16步。按固定次序，达到任一数量上限立即停止整个诊断，不跳过昂贵分支后挑剩余winner。它们是成本上限，不是经验安全阈值或完备性保证。

实际实现 PR 必须在执行前明确冻结静态分解遍历、optional-only tile 的处理与遍历顺序，以及数量上限/时间/错误的停止判定顺序，并经过最终独立 Review；本次设计接受不替代这些实现细节的核对。

一个普通直接JVM、`-Xmx512m`，父进程120秒墙钟上限（含求解和最终replay），总job最多10分钟含clean-build/preflight；不重试、不加节点/时间或换排序救结果。枚举次数不限制单次搜索内部成本，进程超时保留partial，不计正常miss；Xmx不是总RSS上限，需记录实际peak RSS或明确unavailable。若正常干净构建就超预算，记环境/集成失败，不压缩检查。

保存源码/协议/配置SHA、干净checkout、依赖classpath和JDK/JVM、关键class来源/hash、节点/分解/枚举/replay计数、bag边界、每步type/四格/动作/棋盘/角色holes/原guard拒绝原因、终态和停止状态。生产replay必须从空棋盘、原spawn-gravity边界重新验证，不仅相信求解器输出。生产guard对精确构建前后都须通过。沿用完整目录always artifact与独占输出思想，但现有helper的文件一致性标记不能冒充新的构建来源认证。

## 成功标准、停止条件与后续门

诊断成功只指：在固定投入内得到至少一条完整可独立生产replay、7-bag前缀兼容、无消行、终态Q成立的证书，实际class来源/原始记录完整；新增反例与最终SHA独立Review/适用CI通过。人工fixture、静态tile cover、未经重放动作、仅有hash或CI绿色都不算成功。

反例至少覆盖：可拼但不可落；旋转/tuck必须依生产搜索；重叠/缺REQUIRED/侵入FORBIDDEN；support/outside额外hole终态；7-bag重复；中途消行；错误spawn边界；class shadow/旧target；记录截断/预算耗尽。终态证书应另有独立checker验证，不能直接复用求解器的“成功”布尔值。

无证书、超时、来源/集成/重放失败均停止这个假设的实施路径，保留证据，不修改原投入重跑。成功也不解锁#95/#96：先分析证书中哪些前缀必须违反普通guard、需多少额外hole及未知下一块会破坏哪些义务；这些是描述，不据此选阈值。之后才另交在线策略设计，明确不读证书未来序列、不沿seed硬编码、如何放弃/重算以及独立构造安全边界。若无法建立有根据的中间安全条件，可明确否决在线化。

## 接受决议与下一步

依据用户2026-10-27对固定预算、test-only、无新增外部费用的离线研究自主决策授权，接受上述一次终态分解/顺序证书诊断。下一步为单独实现、验证、独立 Review 和适用 CI，完成后执行一次登记诊断；无须再为该研究问题重复等待用户确认。原节点/分解/枚举/内存/时间预算和失败停止条件不变；不接受在线策略、放宽 safety 或启动新 population。后续若提出实质不同的离线假设，仍须先记录证据与协议，通过相同设计审查流程，不能换名挽救本次失败结果。

生产结构、配置与运行能力不变；AGENTS 同步离线研究授权与阶段推进规则，研究索引同步本稿的接受范围，无需新增 ADR。设计接受不能写成“HEART构造已实现”。

## #140 静态分解实现冻结（阶段一）

`TerminalCertificateGeometry` 仅放正常同包测试源码。本阶段没有 CLI、顺序求解器、独立证书 checker、来源监督或完整诊断执行入口；标准测试只枚举人工小域，HEART 仅核对角色 mask，不进行登记搜索。它输出的是静态 cover，不是终态 Q、7-bag 可行、可落或构造成功证明。

- 局部坐标为底部八行的 `(row, col)`，row 0 对应真实 board row 12。required/allowed mask 从生产 HEART 角色读取，allowed 包含 required、support、outside；不包含 forbidden。
- 每种 type 按生产 enum 顺序 I/J/L/O/S/T/Z；从工厂 spawn 开始连续四次顺时针旋转，按最小 row/col 归一化，去重后遍历全部域内平移。tile 全序为 type 后四个 row/col 排序坐标的字典序；HashSet 只检测重复，不提供遍历次序。静态 mask 不判落地、支撑或动作可达性。
- DFS 从空集合开始，每次选择 row-major 最小未覆盖 REQUIRED，按上述 tile 全序尝试覆盖它的 tile。重叠、越 allowed、或 union 满行的分支不进入递归；满行检查只是拒绝无消行终态几何族，不实现消行规则。每个 cover 的 required-touching tile 因 anchor 顺序只生成一次。
- REQUIRED 全覆盖时先同步交给 consumer 一个不可变 tile 列表，然后继续枚举 optional-only tile 的所有互不重叠子集，按 tile 全序递增索引加入，先短前缀后其 DFS 扩展。optional-only 指四格均非 REQUIRED；它们只在 required cover 后加入，避免排列重复，不省略零个 optional tile 的 cover。最多16 tile；每个 cover 都消耗分解计数，包括将来被 Q/7-bag/动作 checker 拒绝的 cover，不通过后验结果改变遍历。
- 节点计数包含空根和每个实际进入的非重叠、无满行递归状态；扫描失败的 tile 不计节点。达到第200,000个节点仍处理该节点，之后不扩展或访问兄弟。分解计数包含每个 REQUIRED 全覆盖节点（含 optional 扩展），第128个仍同步交给 consumer，之后停止整轮。consumer 必须在同一调用链中运行未来的顺序检查；不能先采满128个后再另开一轮筛选。
- 同一节点的停止优先级：异常直接向上抛出，不能降为 miss；同步 consumer 返回后先记分解上限，其次 consumer 主动停止，再记节点上限；最后才继续扩展。`CONSUMER_STOP` 本身不表示成功，必须由独立 checker 结果证明；`EXHAUSTED` 也仅表示此静态域遍历完。父进程120秒/JVM512m/总job10分钟、全局2,000次生产枚举仍待完整监督与顺序实现，不用这里的计数冒充进程边界已执行。

上述顺序与计数在完整诊断前冻结，不因结果改变。完整运行的 TIMEOUT/来源/重放错误优先按原设计记 INCOMPLETE/ERROR；正常计数停止且无完整证书才记 BOUNDED_MISS，不能仅凭静态 `Result` 生成诊断成功/失败结论。后续接线须证明干净 Wrapper/普通 classpath 同包调用、独立 replay、原 guard 与最终 Review/适用 CI，才可执行一次登记诊断。

CI 范围核对：现有 `ci.yml` 将 `src/test/java/*` 视为 Java/build 改动，会执行 Wrapper verify、SpotBugs、核心 PIT 与相应质量门。本阶段没有修改 selector/workflow；`ai-benchmark-pr.yml` 未识别 TerminalCertificate 路径，预期 benchmark=none，不声称运行新工具或固定人口。专用 verify-only/显式诊断与 artifact 接线另行审查，不能借 none 绕过执行前的原 frozen guard。

## #140 顺序与独立 replay 实现冻结（阶段二）

本阶段的 `TerminalCertificateOrder`、`TerminalCertificateStep` 和 `TerminalCertificateChecker` 仍仅在正常同包测试源码。没有 CLI、来源监督、完整诊断接线或登记执行；人工小域找到 order 不表示 HEART Q 或诊断成功。生产结构与默认运行方式不变，无需修改 README/AGENTS。

- 每个静态 cover 先验证工厂生成 tile 身份、无重叠、REQUIRED 全覆盖、无满域行及最多16块，再按 type/四格坐标全序遍历。DFS 从真实空20×10棋盘开始，当前7-bag内重复 type 不进入边；七种齐全才清空 bag。每条边使用原 spawn 后首次 down 的 snapshot，调用一次完整 `ActionPlanCandidates.ranked`，按原排名取第一条无消行且 resultingBoard 等于预期 union 的路径。相同 union 的其余路径不改变后继状态，不重复展开。
- 同一个顺序求解器实例拥有跨 cover 累积枚举/replay计数，最多2,000次完整枚举。枚举前增加枚举计数；匹配边在生产 `ActionPlanSimulator` 再验证前增加 replay计数，异常原样抛出，不能降为 miss。仅人工 fixture 可降低 cap，不能提高。单次完整枚举内部成本仍由后续父进程墙钟监督控制。
- 第2,000次枚举可以完成当前匹配边并返回完整 order，但不能进入下一条边或兄弟。达到上限时 `Result.stop=ENUMERATION_LIMIT` 优先于 `ORDER_FOUND`，完整 order 可同时存在；后续接线必须先独立检查该 order、终态 Q 和来源，再作成功判定，否则按整个诊断的数量停止规则结束。不能为下一 cover 新建实例获得额外额度。异常优先于一切正常停止。
- 每步不可变记录 type、真实 spawn cells/rotation、绝对四格、动作、完整200-bit post-board 与 clearedLines。独立 checker 不读取 solver 状态、缓存 board 或枚举器；从空棋盘逐步核验 bag、spawn、域内四格/不重叠，生产 replay 验证实际无消行及完整 board，最后检查 REQUIRED 并返回原 `GoalWindowCandidate.terminal` 事实。调用者必须额外要求 `terminal.accepted()`，不能把人工域 cover 的通过当作真实 Q。记录截断、伪造 spawn/actions/cells/board 和消行均拒绝。

后续独立短 PR 承接普通 classpath clean-build/provenance、全局监督与实际接线的 Review。完整实现最终 Review、适用 CI 与原 frozen guard 通过之前，不执行完整 HEART 静态搜索、顺序诊断或新 population；保留原120秒/512m/10分钟及一次执行停止边界。

## #140 普通 classpath 预检（阶段三第一部分）

`TerminalCertificatePreflight` 仅接受普通 JDK27 JVM 的 `-Xmx512m -cp <classpath> xyz.xuminghai.tetris.ai.TerminalCertificatePreflight --verify-only <checkout-root>`。没有 collect/default/search 模式，不调用几何或顺序求解。拒绝命名模块、额外 JVM 参数、环境注入、错误 checkout、关键类重复资源、链接目录和来源不符；记录关键生产/测试 class 的真实目录与 SHA-256。仅加载类而不初始化，不用私有成员反射。

执行范围为可提供 `ProcessHandle.Info.arguments()` 的 Linux JDK27；当前 Windows JDK不提供该字段，必须拒绝，不能猜测参数或忽略校验。标准测试在 Linux 启动真实普通 JVM 验证来源、shadow、错误根目录及参数拒绝；Windows 专门验证参数不可得时拒绝，在 Windows 上明确跳过三个 Linux 专属场景。所有平台均验证禁止隐式/collect入口。这些测试不执行完整 HEART 搜索。

输出始终标记 `diagnostic_started=false`、`provenance_authenticated=false`、`eligible_for_runtime_validation=false`。观察到的 hash 不证明 target 新鲜、源码对应或独立来源认证；干净构建与外部 manifest 比对、旧 target 拒绝、构建前后原 guard、120秒/10分钟监督、独占完整输出和一次显式诊断仍待后续交付。不得用本入口成功代替这些门。

本阶段不改变 workflow/selector；标准 CI 的 Java/build 路径通过 Wrapper verify 实际执行本测试，benchmark selector 仍为 none。生产结构与默认运行不变，README/AGENTS 无需同步；本节记录新增测试工具的运行限制。

## #140 干净构建来源认证（阶段三第二部分）

`scripts/prepare_terminal_certificate_build.py` 是 Linux 专属 verify-only 工具。`--root <checkout> --output <外部新目录>` 要求干净源码（含未跟踪、ignored source/build input、index 隐藏标记与链接检查），前后执行原 HEART production guard；用固定 Wrapper 3.9.16 执行 `clean test-compile` 和固定版本依赖清单命令。外部目录独占创建，保留失败 build.log 前缀，不覆盖已有尝试。编译后的全部文件（包括资源）、显式依赖 JAR、源码、设计/原协议、JDK 身份、构建命令和日志绑定到独立 `terminal-certificate-build-v1` manifest。拒绝主/测试类 shadow、依赖中的项目类及 JAR manifest 隐式 Class-Path。

调用方须在目录之外保留准备命令返回的 `manifest_sha256`，再以 `--verify <manifest> --manifest-sha256 <digest>` 核验源码和全部构建字节，启动固定 `-Xmx512m` 普通 JVM 预检，并逐项比对17个类的真实来源/hash；预检后再次核验构建。成功只标记本地未被修改构建的来源认证，不认证恶意宿主、编译器或依赖发布者；不执行求解器，`diagnostic_started=false`、`eligible_for_runtime_validation=false`。

标准 Wrapper verify 中新增 Linux `TerminalCertificateBuildTest`：在独立干净 checkout 做真实 clean build/普通 JVM 认证并拒绝后加编译资源，同时执行 Python 人工来源反例。浅 checkout 只在缺少原协议 base 时 fetch 固定 public commit；非 Linux 跳过此真实构建测试。人工反例包括旧 target 清除、构建失败前缀、manifest/源码/资源篡改、类和依赖 shadow、classpath 顺序、观察来源缺失/多余/hash错误及链接拒绝。人工 fixture 不替代真实 Linux 认证。

本阶段没有更改旧 GoalWindow helper、原 guard、冻结协议或 benchmark selector。新的 Java 测试触发常规 CI；benchmark 仍为 none。生产结构/默认行为不变，README/AGENTS 无需同步。120秒（含独立 checker）父进程监督、512m诊断 JVM、总job10分钟、完整失败输出与显式一次诊断接线仍待下一阶段，不能用本工具的认证成功代替完整诊断或采用门。

## #140 verify-only 进程监督（阶段三第三部分）

`scripts/supervise_terminal_certificate.py --root <checkout> --output <外部新目录>` 仅在 Linux 运行，不接受 collect/solver 模式。外部目录独占创建，先保存 INCOMPLETE report，再监督专属 prepare worker；从其输出单独保存 manifest digest，交给 verify worker。verify worker 仍调用既有固定 `-Xmx512m` 普通直接 JVM 预检，前后核验来源；整个 verify worker（含来源核验）最多120秒。每个 worker 与其 Maven/JVM/git 后代共用独立 POSIX 进程组，复用旧 supervisor 的生命周期函数，未复用 GoalWindow 启动、配置或来源判定，也未修改旧 helper。

总job从输出创建后计时600秒，两个阶段共享剩余预算，固定预留10秒用于进程组 TERM/KILL、回收与报告落盘；每个阶段只得到 `min(阶段上限, 总剩余-10秒)`，不能追加预算或重试。到时保留 stdout/stderr、外部 build.log/manifest 及所有已有失败前缀，报告原始进程 state/returncode；TIMEOUT、INTERRUPTED、非零退出、错误JSON/来源或deadline耗尽均是 INCOMPLETE，绝不标为 BOUNDED_MISS。成功仅为 verify-only VERIFIED / provenance_authenticated=true，始终 diagnostic_started=false / eligible_for_runtime_validation=false。peak RSS 明确 unavailable；这不是未来真实诊断的RSS证据，也不宣称能对宿主停顿或不可中断内核IO提供硬实时保证。

标准 Linux `TerminalCertificateBuildTest` 的实际独立 clean-build/17类核验改由此入口完成，继续验证随后篡改资源被拒绝；另从标准测试运行人工阶段/总预算/错误结果/独占输出及真实超时、非零退出的Python反例。Windows跳过真实进程组场景。selector/workflow不变，benchmark none不算运行诊断。生产与运行结构未变，无需更改README/AGENTS。

本阶段仍没有完整HEART静态搜索、求解CLI、逐步证书输出或最终checker接线。完整诊断的单直接JVM120秒（含独立checker）、实际诊断类来源核验、全局计数、完整raw/schema和显式一次执行，须后续独立短PR冻结并通过最终Review/适用CI/原guard；不能拿本次普通预检监督冒充已经满足全部执行门。

## #140 原始逐步证书与独立文件核验（阶段四第一部分）

`TerminalCertificateRecords` 定义 `terminal-certificate-steps-v1`：根字段仅有 schema、step_count、steps；每步按连续零起点 index 保存 type、spawn_cells、spawn_rotation、绝对 cells、actions、完整200-bit post_board（小写十六进制、无前导零）和 cleared_lines=0。保留原列表顺序，不排序或补齐输入。只接受1–16步、四个不同坐标、整数和已知枚举；拒绝截断、重复/缺失/未知字段、尾随JSON、类型强制转换、超界棋盘及超过1MiB的文件。1MiB仅限制记录解析资源，不改变搜索预算。独占创建文件，不覆盖此前失败前缀；文件完整性不证明搜索完整性。

`TerminalCertificateReplay --check <certificate-steps.json>` 仅做显式离线文件核验，不调用几何/顺序搜索。从真实空棋盘按原spawn-gravity边界调用独立checker，逐步核验7-bag、四格/动作/完整post-board、无消行，并额外要求固定HEART域和最终Q。只有全部通过才输出 REPLAYED_Q 与重新计算的终态事实、checker replay计数和final_board；人工小域、失败Q或篡改文件不能产生此结果。输出始终 diagnostic_started=false、provenance_authenticated=false、eligible_for_runtime_validation=false。它不认证源码、实际类来源、预算、求解过程或记录生产者，不能视为一次登记诊断成功。

标准Wrapper测试只对人工小域执行往返与生产replay，覆盖截断/重复字段/类型/动作/棋盘篡改、超大输入、独占输出和禁止collect入口。尚无真实HEART证书，因此本阶段没有CLI成功路径的真实HEART证据。未改普通preflight的17类清单、build manifest、supervisor或selector；新类的实际来源认证、逐步role-hole/原guard事实、完整搜索CLI、全局计数与失败raw接线仍须后续短PR完成。只有整体最终revision独立Review/适用CI/原guard通过后才执行一次登记诊断。生产结构与默认运行未变，README/AGENTS无需同步；不解锁population或runtime。

## #140 同步求解 session 与逐步事实（阶段四第二部分）

`TerminalCertificateSession` 在正常测试包中同步连接静态 cover consumer、单一 `TerminalCertificateOrder` 与独立 checker。同一个 Order 实例跨全部 cover 累计2,000次生产枚举；几何200,000节点/128分解/16步不变。仅人工小域允许降低上限，不能提高。标准测试不调用完整 HEART 搜索，没有公开 CLI 或 collect 接线。

每个 cover 的完整 order（包括最后允许的一次枚举刚好完成的 order）先从空棋盘独立 replay，再判断终态 Q，最后返回 consumer 停止信号。只有固定 HEART domain 且 Q 通过才返回内部 `Q_FOUND`；人工域的完整重放仍为 `BOUNDED_MISS`。数量上限不抹掉已核验的 Q：若 Q 与枚举/分解/节点上限同时发生，保留 Q 和原几何 stop/枚举上限标记。没有 Q 才按数量停止或该受限域遍历结束记录 miss；checker、生产搜索、观察输出异常直接传播，不能转换成正常 miss。`Q_FOUND` 仅表示内存中的求解/重放结果，不包含来源认证、记录持久化或完整诊断成功。

每条所选完整路径附带不可变逐步事实：连续 index、7-bag 前后 mask（第七种后清零）、SURVIVAL rank、原 `GoalWindowCandidate.guard` 的 top-5/DANGER/budget及三个delta/risk/profile、生产board的角色 holes/高度/clean facts。FORBIDDEN holes 为 raw减REQUIRED/SUPPORT/OUTSIDE；bag事实与动作/棋盘记录逐项对齐。guard与角色事实直接取自该边已计数的完整候选枚举及生产replay，不另开枚举、不影响遍历或 admission；普通 baseline rank1 保持生产无条件允许的描述口径。回溯时同步丢弃未选路径事实，后续 cover 不继承旧事实；搜索/replay计数则不清零。

观察者在同一 cover 调用链内接收 attempt（含累计计数、完整order/facts与独立checker结果），先于几何数量停止返回；失败直接终止调用链。它为后续原始文件输出保留同步接点，本阶段没有持久化 attempt/raw/session envelope，也不提供异常/超时完整报告、真实诊断类来源或墙钟监督。现有严格 steps-v1 不变；新 session/facts 尚未纳入17类预检清单。上述缺口须在后续完整CLI/来源/外部监督接线中交付并对最终整体revision独立Review、适用CI与原guard，通过后才登记一次诊断。README/AGENTS的生产结构及运行方式未变，无额外采用权限。

## #140 原始 session journal（阶段四第三部分）

`TerminalCertificateJournal` 仅在测试包提供同步 session 的显式内部调用，不是公开 CLI。独占新建调用者指定文件，输出 `terminal-certificate-session-v1` JSONL：start 保存域、预算与 INCOMPLETE；每次同步 attempt 保存累计枚举/replay、order stop、原 steps-v1 完整声明、对应 bag/rank/原 guard/角色 holes/Q 事实及独立 checker 的结果。没有完整 order 的尝试明确 steps/checked=null、facts=[]，不伪填重放或终态事实。finish 保存原几何 nodes/decompositions/stop、全部最终计数、枚举上限及证书 decomposition；search_status 保留内部 Q_FOUND/BOUNDED_MISS，不将内部结果升级为诊断成功。

每条完整事件写后执行 FileChannel.force(true)，不等待正常 close；进程中断、写失败或最后一行截断时保留已有前缀。没有完整 finish 必须视为 INCOMPLETE，不能以最后一个 attempt 或 Q 字段推断成功。捕获到的异常尽力追加 ERROR 与异常类，仍向上传播原异常；记录失败仅作为 suppressed，不生成正常 miss 或重试。文件系统/宿主故障不能保证最后一条事件或目录元数据持久性，force 不代替外部监督或完整性核验。已有文件（含失败/截断前缀）拒绝覆盖。

标准测试只执行小人工域，验证真实生产文件 replay、完整与未完成 order、计数停止、零尝试节点停止、错误传播与独占输出；没有完整 HEART 诊断、人口或成功证书数据。所有事件保持 provenance_authenticated=false 和 eligible_for_runtime_validation=false；本组件不认证来源、进程预算或最终诊断。steps-v1 的文件写入与 journal 嵌入共用同一严格验证和1MiB记录上限，不改变格式或搜索预算。

生产结构及默认行为不变，README/AGENTS无需同步；workflow/selector仍不变。公开诊断CLI、完整失败目录/原始完整性核验、实际新类来源认证和512m直接JVM/120秒含checker/600秒总监督尚待完整接线，整体最终独立Review、适用CI与原guard通过后才可登记一次执行。本阶段不授予95/96/reserved/provider/runtime权限。

## #140 journal 严格消费与独立文件重放（阶段四第四部分）

`TerminalCertificateJournalReader.read` 只读取既有 `terminal-certificate-session-v1`，不调用几何或顺序搜索。按行读取，单行最多2MiB、最多130条完整事件（start、128 attempts、finish）；不把整个文件装入内存。2MiB是1MiB steps-v1加最多16条事实和信封的解析上限，不扩大搜索预算。完整行严格解码UTF-8、拒绝重复字段/尾随JSON/未知或缺失字段、类型强制转换、非法枚举、超限/非规范mask和任何权限true声明。

消费状态只能从start进入attempt，再到finish或error；setup异常允许单独error。连续decomposition、累计枚举/solver replay、checker计数、枚举上限、几何计数与停止优先级、Q与证书decomposition必须相互一致。每个完整order复用严格steps-v1，从空棋盘独立调用原checker，不重做候选枚举；比对完整final_board/终态、逐步bag与生产board角色holes事实，并检查rank/top-5/ordinary以及原guard字段类型、risk/profile内部一致性。rank、baseline与三个guard delta仍只是生产者声明；本读取器不重枚举以认证它们，也不能证明完整搜索覆盖或真实节点计数。文件可自洽但来源不可信，不能因此推断BOUNDED_MISS为登记诊断结论。

返回COMPLETE只表示有完整finish且上述文件/重放核验通过，既不是诊断成功，也不是来源认证。空文件、缺finish或未换行/截断末行返回INCOMPLETE，最后attempt/Q不能补全它；显式error返回ERROR；完整但损坏/矛盾的事件抛出错误，由未来外层监督保存为失败，不能转成正常miss。terminal事件后的任意字节（包括空行、截断JSON）拒绝。读取失败不覆盖原文件或重试搜索。

标准Wrapper人工小域测试覆盖真实journal往返和独立replay、各事件边界及截断finish、setup ERROR、终态后字节、重复/未知/缺失/错误类型、权限提升、计数/stop/证书矛盾、board/bag/角色事实篡改、非法UTF-8与超大行；一节点零尝试fixture只验证停止记录，不执行完整HEART搜索。本阶段仍没有公开诊断CLI、实际新类来源认证或完整120秒/512m/600秒诊断监督接线；整体最终Review/适用CI/原guard通过前不执行一次登记诊断。生产结构/运行方式未变，README/AGENTS无需同步；不解锁95/96/reserved/provider/runtime。

## #140 显式 CLI 与实际 session 类来源（阶段五第一部分）

`TerminalCertificateApplication` 仅接受普通 JDK27 的 `-Xmx512m -cp <classpath> xyz.xuminghai.tetris.ai.TerminalCertificateApplication --collect <checkout-root> <new-external-output>`。没有隐式模式、verify/search 混合模式或参数预算覆盖；固定 HEART 域和已登记的200,000节点/128分解/2,000枚举/16步。同 JVM 先观察真实类来源，独占新建 checkout 外的规范目录，保存 observation、INCOMPLETE started、原 forced journal，再由严格 reader 独立文件重放。所有计算、两次 checker 和文件读写均属于后续父进程的同一个120秒诊断窗口，不能给 reader 追加预算。

只有 journal 完整且其计数与内存 session 一致才独占写入 completion.json；状态为 SESSION_COMPLETE，内部 search_status 保留 Q_FOUND/BOUNDED_MISS，provenance_authenticated 和 eligible_for_runtime_validation 仍为 false。完成文件不是来源或诊断成功证明。异常尽力保存 error.json、原样传播，不写正常 completion、不重试；目录存在时在任何写入前拒绝，保留原失败字节。缺 completion（包括终止/截断）保持未完成；force 的宿主/文件系统局限与原 journal 相同。无 CLI 成功路径的真实 HEART 证据，本阶段测试只运行人工小域。

普通 Preflight 的公开入口仍仅为 verify-only，不调用 solver；共享内部参数/来源观察用于显式 CLI。Java 与 Python 外部 manifest 清单从17项扩至23项，新增 Application、Session、Order$StepFacts、Records、Journal、JournalReader；嵌套 StepFacts 使用实际二进制名。全部编译字节清单继续绑定其余嵌套类。Linux 独立 clean-build 测试实际核对23类，普通 JVM 用逐个额外同名资源反例拒绝六种新类 shadow；仅加载、不初始化这些类，预检不会执行完整 HEART 搜索。

完整父进程 collect 模式尚未交付：仍须在同一总600秒窗口内串接 clean build、外部 manifest digest、CLI 前后来源/原guard核验、单直接512m JVM120秒（含reader/checker）、完整失败前缀和最终诊断报告。现有 supervisor 继续只执行 verify-only，不因新增 CLI 自动执行诊断。整体最终 revision 独立Review、适用CI及原guard通过前不登记一次运行。本阶段未改变生产、原guard、冻结协议或workflow/selector；README/AGENTS所述生产结构和默认行为不变，无95/96/reserved/provider/runtime授权。

## #140 父进程 collect 监督（阶段五第二部分）

`supervise_terminal_certificate.py` 默认仍为 verify-only；只有显式 `--collect` 选择固定预算诊断。独占外部目录后先写 INCOMPLETE report，在原600秒总窗口（含固定10秒终止/落盘预留）中顺序执行 prepare、before认证、单直接诊断JVM、after认证。before/after 使用新增 `prepare_terminal_certificate_build.py --verify ... --manifest-sha256 ... --authenticate-only`，核对同一独立保留digest、源码/完整编译字节/依赖/JDK及原生产guard，不启动preflight或solver。JDK身份核对仍执行 `java -XshowSettings:properties -version` 探针，其成本包含在对应认证worker和600秒总窗口内；它不是诊断JVM，也不运行搜索。默认 verify-only worker 行为不变。

诊断命令固定 `-Xmx512m -cp <manifest classpath> TerminalCertificateApplication --collect <root> <外部raw新目录>`，整个直接JVM最多120秒，包含session、内存checker和严格文件reader；每阶段仍受总剩余减预留约束。只有这一处诊断启动，不重试、不更换顺序/预算。即使JVM超时或非零退出，也在剩余总预算内尝试after认证；若无剩余预算则记录未完成，不能追加时间。原始进程state/returncode、所有stdout/stderr及build/raw失败前缀原位保留；无法确定是否成功启动时 diagnostic_started=null。

父进程将CLI observation与23类manifest逐项比对，核对独占四文件、INCOMPLETE start、stdout/completion一致、完整journal首尾与固定计数预算，并逐行有界记录全部raw的SHA-256。完整语义/生产replay由同一已认证JVM中的独立Java reader负责，Python不复制游戏规则或rank/guard判定。只有前后认证和文件绑定全部成功，父报告才接受Q_FOUND/BOUNDED_MISS；TIMEOUT、缺completion、截断、来源错误或总deadline耗尽均为INCOMPLETE。eligible_for_runtime_validation始终false。peak RSS明确unavailable（生命周期工具未采样），Xmx不表示总RSS。此本地主机认证不抵御恶意宿主/编译器，也不承诺对不可中断IO硬实时终止。

标准测试只用人工worker/files验证命令、计数/类hash/预算/截断反例、前置失败不启动、超时后认证、不重试和输出独占；Linux原有真实clean-build预检与真实进程组超时测试继续执行。没有完整HEART搜索、collect成功路径实测或登记诊断结果。本阶段不修改生产、旧guard/冻结协议、GoalWindow helper或workflow/selector；README/AGENTS生产结构和默认行为不变。整体最终revision独立Review、适用CI及原guard通过后才可按既定协议执行一次诊断。

## #140 Linux 显式执行接线（阶段五第三部分）

专属 `.github/workflows/terminal-certificate.yml` 只提供 `workflow_dispatch`，没有 PR/push/schedule/reusable 入口；不修改既有 benchmark selector、GoalWindow workflow、协议或原 guard。`collect=false` 为默认值，仅运行已交付的 clean-build/普通 JVM verify-only。显式 `collect=true` 才执行一次既定诊断，参数不能覆盖域、节点、枚举、内存或时间预算。

入口限制同一仓库、当前 default branch ref、完整 lowercase `expected_sha` 等于 dispatch SHA/checkout SHA，并在启动前读取远端 default ref 再次核对；分支已推进即停止，不静默换 revision。collect 拒绝 `run_attempt != 1` 的 Actions rerun；静态 concurrency 排队而不取消运行中的 session。它不阻止有权限的操作者另开一次 dispatch，所以必须先在 #140 登记唯一执行的精确 SHA、最终整体独立 Review、适用 CI 与已冻结预算，再显式启动；结果失败/超时后不得另开 dispatch 重试。新增入口不替代这些前置。

runner 固定 `ubuntu-24.04`/JDK27，token 只有 contents:read、checkout 不保留凭据，不读取 provider secret。整个 Actions job 上限10分钟（含 setup/上传），父监督仍为600秒总窗口、120秒单直接512m诊断JVM，均不追加时间；平台 job deadline 可能更早中止 supervisor，必须记为 INCOMPLETE。预检不表示完整诊断执行。未增加 population/seed/warmup、外部费用或 runtime/default adoption 权限。

checkout 外独占 run-id/attempt 目录先写 INCOMPLETE dispatch context；包括 supervisor report、build/manifest、前后 guard/来源观察、完整 stdout/stderr、raw journal/completion/error/失败前缀在内的整个目录由 `always()` artifact 保存30天，包含隐藏文件。没有成功报告时不能把 workflow绿色、artifact存在或内部 Q 当作成功。硬 job timeout、宿主中断或 artifact 服务失败仍可能使 always 上传无法完成；此时来源/结果保持未核验，不允许因此重跑诊断。平台耗时与报告的 supervisor elapsed 分开解释。

这是执行接线交付，尚未登记或执行完整 HEART 诊断。必须先以最终整体 revision 独立 Review/适用 CI/原 guard 确认触发、权限、失败产物与整条来源链，再合并至 default branch。生产结构/默认运行方式不变，README/AGENTS无需同步；#95/#96/reserved/provider/runtime继续停止。
