# 固定 witness：构建存储与 10MiB 证据边界

状态：2026-11-09 用户明确接受方案 A；本次新增修改实施 prepare 存储分离，
**不解锁完整验收或历史执行**。下文分析、备选与当时待确认状态保留为历史证据。

## 方案 A 的明确接受与实施

用户在 Codex「查看开发进度」会话 `01a1bdd5-5d02-7580-95b4-42302a6fa60a`
中对完整的方案 A 风险说明答复「接受方案 A」（消息
`01a1be5a-b3ca-7302-a36e-cb291b66b584`）。这项直接授权取代下文历史待确认状态，
不是以 PR 合并、CI 或沉默推定接受。用户同时接受的 #174 任务预算修订须另行实施，
本次不实现或宣称具备任务创建前监督能力。

`prepare(..., build_storage=...)` 现在要求调用者提供独立、已存在的空构建目录，
与源码、JDK、证据目录逐对禁止相等或嵌套，保持 canonical/no-link 检查。
HOME/TMP 新建在构建目录内；正常 clean-build 的 target 仍在源码 checkout 内，
不改变构建输入、不借用预编译 class。before/manifest 记录 policy、evidence、
build_storage、home、tmp、target 实际路径和 `build_disk_hard_limit=null`；
receipt 复核同时验证分类、路径和环境一致性。不存在的目录不代建，非空目录拒绝，
不删除或覆盖调用者数据。

构建缓存/下载/target 单独分类，**不提供构建磁盘硬上限，也不构成 OS 写隔离**。
stdout/stderr、JSON、classpath 提取、握手、journal/report、失败前缀及额外证据副本
仍须共用原 10MiB 根额度。prepare 保留写前计费、失败不退款、同根 failure reserve；
不能把日志搬到构建目录或改名为缓存逃避计费。诊断阶段的只读挂载及全部 writer
接线仍须完成独立验收。

## 以下为原 PR 的历史分析
接续 [PR #169](https://github.com/xuMingHai1/tetris-ai/pull/169) 和
[#91 领取](https://github.com/xuMingHai1/tetris-ai/issues/91#issuecomment-6606901526)。
分析基线为 default `main@e71826c0e12670319eb53015f491cefb677ecf55`。

## 已核验事实

[原设计](../design/heart-fixed-witness-replay.md) 同时要求：

- 干净 Wrapper `clean test-compile`，构建可下载已有依赖。
- 父监督合计输出最多 10MiB（10,485,760 字节），超限立即终止并保留前缀。
- 保存 stdout/stderr、环境、配置、命令、class/source hash 和报告。

原设计没有逐项划定构建缓存、下载临时副本和 `target` 是否属于这项“输出”。
[环境组件](heart-fixed-witness-replay-environment.md) 要求新建空 HOME/TMPDIR；
[prepare](heart-fixed-witness-replay-prepare.md) 又把它们放在 output 目录内。
物理位置不能自行决定是否计费，历史阶段记录也不能替代明确的冻结范围。

本轮只检查上述 SHA 的 Git 对象和脚本，没有重新构建或执行 Java：

```powershell
git ls-tree -r -l e71826c0e12670319eb53015f491cefb677ecf55 -- src/main/resources
```

该命令列出 15 个资源 blob，长度合计 **22,863,292 字节**；其中
`src/main/resources/audio/bgm.wav` 单文件 **21,635,116 字节**，blob 为
`f76870b568f79939133727c077a5961dad423fe9`。这是源码 blob 长度，不是压缩包大小、
RSS、诊断输出或新的 Linux 实测。现行 POM 没有排除此目录的自定义 resources 规则；
普通 Maven `test-compile` 生命周期会先复制 main resources 到 `target/classes`。
因此若连这些构建复制也计入同一 10MiB，单个 WAV 已足以拒绝正常干净构建，
无需通过运行历史输入来验证。

## 外部 writer 盘点

| writer / 位置 | 当前事实 | 尚未提供的保证 |
| --- | --- | --- |
| Wrapper 下载与展开 / TMPDIR | `mvnw` 的 `mktemp -d`，curl/wget/Java 下载，unzip/tar 展开，之后 move | 下载、展开、中间副本均不经过 ByteQuota；成功后删除也不能证明累计写入受限 |
| Wrapper 安装 / HOME/.m2/wrapper/dists | 未继承 MAVEN_USER_HOME，Wrapper 使用 `${HOME}/.m2` | 新空 HOME 使冷启动需要安装；安装体积本轮未测量 |
| Maven 依赖、插件与元数据 / HOME/.m2/repository | prepare 未传 maven.repo.local，MAVEN_OPTS 固定 user.home | 下载和仓库临时写入不经过 ByteQuota；依赖总体积本轮未测量 |
| 编译、资源与状态 / checkout/target | prepare 拒绝已有 target，然后执行正常 clean test-compile | 不是父进程合作式 writer；仅资源复制就超过 10MiB |
| Java 临时及性能数据 / TMPDIR | Maven 和直接 JVM 均显式传 java.io.tmpdir；这不拦截 OS/native 写入 | 没有 OS 写入隔离；也未证明 JVM 不写临时文件 |
| 证据文件与管道 | PR #166–#169 已逐步接入共享额度、启动前永久预留及写前计费 | journal/report、完整监督和全部 writer 尚未接通，不能称 session 总额度已实现 |

以上引用本基线的 `mvnw`、`scripts/fixed_witness_replay_environment.py`、
`scripts/fixed_witness_replay_prepare.py`。不读取用户 Maven 设置、凭据或历史 witness。
表中默认路径来自本仓库的受控调用；不是对任意 Maven plugin/native 行为的沙箱保证。

## 推荐与备选

| 方案 | 影响 / 决定 |
| --- | --- |
| A：明确 10MiB 只管证据输出；构建存储单独分类 | 推荐提交用户确认。stdout/stderr、JSON/JSONL、classpath 提取、READY/verified、journal/report、失败前缀及保留副本仍共用原 10MiB；构建下载/缓存/target 不从中计费。必须明确这不提供构建磁盘硬上限，不能把它描述为所有 session 写入总限额。 |
| B：10MiB 管所有新写入，包括构建存储 | 保留严格解释。现行干净构建不可满足，完整诊断停止；不得删除音频、跳过资源、改构建输入或借用预构建 class 绕过。若继续须另行设计并授权新的存储协议。 |
| C：暂不选择边界，继续原记录 | 安全退路。保存本分析，不执行完整诊断；仍可独立开发不预设分类的 journal 写前计费等组件。 |

A 不在本 PR 中成为接受决定。把此前可能属于额度的目录排除，可能放松冻结范围；
自主 test-only 研究授权不包含修改冻结协议。即使独立 Review/CI 无阻断并合并本文，
也只接受证据分析，**不能据此选 A、排除 HOME/TMP 或启动诊断**。

## 确认后的实施边界

若用户明确选择 A，后续单独实施：分开构建工作目录与证据目录；记录所用分类和真实
路径，保持空 HOME/TMP、来源认证和正常 clean-build。构建阶段的日志仍计费，
不能把日志改名为缓存；证据的额外保留副本也计费。共享 ByteQuota 继续写前计费、
永久预留、失败不退款；目录事后扫描、RLIMIT_FSIZE、仅预留额度以及 tmpfs 的瞬时
容量都不能替代累计字节保证。构建磁盘硬限额若另行提出，须有独立预算/机制设计，
本分析不指定更大额度，也不默认提供这种保证。

诊断阶段应另行评审 OS 级网络/写入隔离：构建输入只读、HOME/TMP 不允许额外写入，
合作式证据 writer 在可写证据目录内按同根额度计费；实际 Linux 验证权限、拒绝路径、
失败前缀和正常普通 JVM。具体隔离工具/可用性本轮未验证，不能据此宣称已可执行。
若必要写入与只读约束不兼容，先报告并复审，不自动另开无额度 tmpfs 或继承环境。

无论选项如何，22 placements、零搜索、600/120/180/900 秒、256/128MiB、唯一执行
及停止条件均保持；不能增加预算、换动作、改 seed 或自动重跑。完整实现最终独立
Review/适用 CI、兼容性与 input hash 刷新、唯一 session 登记仍在历史 22 步之前。
网络/journal/整体监督未完成，#140/GoalWindow 停止，#95/#96、reserved/provider、
runtime/default 权限不变。

本文不修改生产架构、运行方式、资源或构建，不需更新 README/AGENTS/ADR。
