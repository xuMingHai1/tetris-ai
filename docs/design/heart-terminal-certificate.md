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
