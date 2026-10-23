# HEART 下一构造方向：终态分解与落子顺序证书

关联 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)，承接 [GoalWindow 停止结果](../research/heart-goal-window-result.md)与[机理复核](../research/heart-goal-window-mechanism.md)。

**状态：待用户接受的设计 Draft，不是新的候选许可。** 核对主干 `dc98424d808b6da462c0fd1bdb9a56720c4bc8a8`。本次仅分析和设计；GoalWindow 保持否决，不重跑、不改 Q/rank/seed/budget。#95/#96、reserved/provider/runtime/default adoption 全部保持停止。独立设计 Review 的无阻断结论只证明稿件可评审，不能代替新假设的接受。

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

## 实现范围与冻结边界（仅在接受之后）

建议新建独立诊断任务，不重开#111或把它计作#94的继续采集：

- `src/test/java/xyz/xuminghai/tetris/ai/TerminalCertificate*.java`：同包test-only几何分解、顺序诊断、生产replay及人工反例；使用正常包路径，不shadow/reflection，不修改生产API。
- 专属 `scripts/*terminal_certificate*.py`：监督、输入/输出与证书核验；复用已交付build/provenance的可复用部分，不能假称GoalWindow固定人口driver已经支持新诊断。
- 设计接受后单独Review实际接线方案。现有selector不会自动识别新文件；不得假定已有CI已执行新工具，也不得改路由绕开原frozen guard。

`src/main`、资源、pom/wrapper、HEART v1.1/#105分析器和原guard不改。生产 `ActionPlanCandidates`/spawn/role API是package-private，正常test同包访问；不重演#112在非benchmark生产目录加诊断导致guard失败的问题。实际实现PR必须先证明干净Wrapper构建及普通classpath同包调用成立；若不成立，停止，不临时扩大白名单。

本设计不批准采集population，不运行旧GoalWindow warmup，也不使用20000–20079、30000–30199或31000–31002作为新搜索输入。没有真实seed采集或远程模型成本。新workflow若确有需要，先独立审查其权限/触发/产物，不能在本稿中承诺可执行。

## 固定投入与证据

拟议一次诊断的共同预算：几何递归展开最多200,000节点、完整分解最多128个；全部分解共享最多2,000次生产枚举调用，终态最多16步。按固定次序，达到任一数量上限立即停止整个诊断，不跳过昂贵分支后挑剩余winner。它们是成本上限，不是经验安全阈值或完备性保证。

一个普通直接JVM、`-Xmx512m`，父进程120秒墙钟上限（含求解和最终replay），总job最多10分钟含clean-build/preflight；不重试、不加节点/时间或换排序救结果。枚举次数不限制单次搜索内部成本，进程超时保留partial，不计正常miss；Xmx不是总RSS上限，需记录实际peak RSS或明确unavailable。若正常干净构建就超预算，记环境/集成失败，不压缩检查。

保存源码/协议/配置SHA、干净checkout、依赖classpath和JDK/JVM、关键class来源/hash、节点/分解/枚举/replay计数、bag边界、每步type/四格/动作/棋盘/角色holes/原guard拒绝原因、终态和停止状态。生产replay必须从空棋盘、原spawn-gravity边界重新验证，不仅相信求解器输出。生产guard对精确构建前后都须通过。沿用完整目录always artifact与独占输出思想，但现有helper的文件一致性标记不能冒充新的构建来源认证。

## 成功标准、停止条件与后续门

诊断成功只指：在固定投入内得到至少一条完整可独立生产replay、7-bag前缀兼容、无消行、终态Q成立的证书，实际class来源/原始记录完整；新增反例与最终SHA独立Review/适用CI通过。人工fixture、静态tile cover、未经重放动作、仅有hash或CI绿色都不算成功。

反例至少覆盖：可拼但不可落；旋转/tuck必须依生产搜索；重叠/缺REQUIRED/侵入FORBIDDEN；support/outside额外hole终态；7-bag重复；中途消行；错误spawn边界；class shadow/旧target；记录截断/预算耗尽。终态证书应另有独立checker验证，不能直接复用求解器的“成功”布尔值。

无证书、超时、来源/集成/重放失败均停止这个假设的实施路径，保留证据，不修改原投入重跑。成功也不解锁#95/#96：先分析证书中哪些前缀必须违反普通guard、需多少额外hole及未知下一块会破坏哪些义务；这些是描述，不据此选阈值。之后才另交在线策略设计，明确不读证书未来序列、不沿seed硬编码、如何放弃/重算以及独立构造安全边界。若无法建立有根据的中间安全条件，可明确否决在线化。

## 需要接受的唯一决策

是否接受上述**一次受限test-only终态分解/顺序证书诊断**，允许后续单独实现及最终review后执行？推荐接受此诊断问题；当前不请求接受在线策略、放宽safety或启动新population。若不接受，保留本次机理分析，继续比较其他建设假设。

README/AGENTS生产结构、配置与运行能力未改变，无额外架构/ADR修改；研究索引新增本稿为Pending，不能把“设计可评审”写成“HEART构造已实现”。
