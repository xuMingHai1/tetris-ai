# GoalWindow 全部 BASELINE_CHOICE 的机理复核

关联 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91) / [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)。2026-10-23仅只读复核既有采集，不是新测量，也不撤销[候选停止结论](heart-goal-window-result.md)。源码核对主干 `dc98424d808b6da462c0fd1bdb9a56720c4bc8a8`；测量源码仍为 `b837a8f0487ca2ff8741d0909490e16cb9bfffa0`。

## 输入与统计口径

使用原[run39751557172 artifact12413628699](https://github.com/xuMingHai1/tetris-ai/actions/runs/39751557172/artifacts/12413628699)的 `measurement/raw.jsonl`，230,818,747字节，SHA256 `1928af2f29ffc08e90b5e1bbaa63b250c6a90a8adcf95e03fc49ddfd1ce84e38`，与已登记值相同。artifact/完整文件索引、构建前后检查和原型18门见原结果页。本页不修改原始文件、原分析器或冻结配置。

计数只取CANDIDATE的measured turn，不含warmup或BASELINE臂；setup是一个当前候选条目，一次turn可有5个条目，不把它们当作独立局或独立决策。guard拒绝项可重叠，下面单独给互斥组合。原日志不记录每个被拒setup的preview结果，也不记录所有非Q终态，不能补填为0。

## 实际复算

| 观测 | 数量 |
| --- | ---: |
| measured candidate turn / BASELINE_CHOICE | 68,706 / 68,706 |
| IMMEDIATE_Q / PREVIEW_SETUP / windows条目 | 0 / 0 / 0 |
| setup条目，合格 / 拒绝 | 343,521，91,004 / 252,517 |
| 合格rank1 / rank2 / rank3 / rank4 / rank5 | 68,706 / 8,685 / 6,267 / 4,383 / 2,963 |
| 仅rank1合格的turn | 58,223 |
| rank1 risk LOW / NORMAL / DANGER | 3,420 / 15,331 / 49,955 |
| 拒绝：DANGER且budget失败 | 156,068 |
| 拒绝：DANGER且budget通过 | 43,743 |
| 拒绝：非DANGER且budget失败 | 52,706 |

所有turn都有setup，`enumerations`为3–7；结合源码计数，91,004个合格setup均进入preview枚举。不存在“候选没启用”或这批turn“全部缺preview”的解释。每次至少current、一个preview、baseline各一次枚举；保留的原始延迟显示额外成本，不是纯no-op。

setup post-clear事实中的clean全部为false；3,657条达到32 REQUIRED全覆盖，但仍至少10 FORBIDDEN占用。所有setup的最小visual error为9，合格setup的最小值为10；实际selected placement最小值也为10，实际selected全覆盖680次，FORBIDDEN同样至少10。这些是**当前setup/selected**统计，不能描述未逐项记录的preview落点分布，更不能把min error当成新阈值。

## 源码如何解释这些事实

[GoalWindowCandidate.plan](../../src/test/java/xyz/xuminghai/tetris/ai/GoalWindowCandidate.java)先完整枚举当前落点，存在任何Q便立即选`IMMEDIATE_Q`。无当前Q后，只对rank1和通过原guard的rank2–5做preview枚举；其中存在任何Q便选`PREVIEW_SETUP`。两者都没有才调用原生产`BuildShapeActionPlanningAgent`，记录`BASELINE_CHOICE`。`windows`只保存通过Q的条目。

因此，按这份实际实现，日志证明**被完整搜索的当前落点与获准setup之后的preview落点没有Q**。DANGER、budget与top-5缩小了setup入口，但不能说它们拒绝了已经找到的终局Q：当前Q/获准setup后的preview Q不再受终态原guard否决，guard只作为事实保留。rank1即使DANGER仍进入preview；49,955次rank1 DANGER不是“跳过整个候选”的证据。

第一次干预之前两臂都调用同一个生产baseline，candidate又是无状态；本次从未干预，所以没有偏离baseline去主动建设窗口的机制。实际80配对pieces delta全0、双臂0clean与该机制一致，但pieces相等本身不证明每步board相等。

## 缺陷、机会与约束必须分开

| 归因 | 本次支持的结论 | 仍未知 |
| --- | --- | --- |
| 接线/实现缺陷 | raw有实际候选、setup和枚举；现有人工fixture能分别走IMMEDIATE_Q/PREVIEW_SETUP，未找到足以解释全部fallback的已确认缺陷 | 只读审查和历史fixture不是实现无bug的证明；本次没有重跑JVM测试 |
| 机会缺失 | 实际搜索范围内Q窗口数为0；不是找到Q后被终态guard过滤 | 不合格setup、top-5外setup、两步以上，以及clean但非Q的机会不可由此得知 |
| 设计约束 | 精确clean+额外hole/高度Q、guarded setup、一步preview、无状态baseline轨迹决定“只截终局、不建设” | 不能分摊每个Q子条件的反事实贡献；没有全部失败终态的分维直方图 |

不要把“零Q”写成“HEART不可构造”“只需解除DANGER”“只因预算过严”或“调Q就能成功”。这些解释需要日志目前没有的反事实证据；不为补它们重跑已停止候选。原helper的保守provenance=false也不是收益门失败的原因。

## 下一方向

推荐先评审[终态分解与落子顺序证书设计](../design/heart-terminal-certificate.md)：先验证一个新的、受限的建设结构，再考虑在线义务和安全条件。该稿Pending，不修改旧候选，不实现新策略，不消耗保留样本；独立Review和CI不能替代用户接受新构造假设。
