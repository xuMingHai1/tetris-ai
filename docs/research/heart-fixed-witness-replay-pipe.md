# 固定动作回放的 verify-only 父管道组件

接续 [READY 认证](heart-fixed-witness-replay-admission.md)，
`fixed_witness_replay_pipe.run_verify_only` 仅启动已有普通 JVM 的 `--verify-only`。
独立 receipt 预检后构建精确命令，JVM 等待 READY/ACK；认证期间继续排空 stdout/stderr，
只有监督主线程能写入至多512字节的单次非阻塞 ACK，并立即关闭 stdin 交付 EOF。
认证拒绝、超时、取消、提前退出或 pipe 输出超限都不能释放 ACK；退出码非零不能成功。
READY 文件出现但未写完 LF 时继续等待，避免将 CREATE_NEW 与实际写入之间的窗口当错误。

退出后在同一个截止时间内复核独立 receipt、READY 精确字节及 verified 的 schema、
状态、READY hash、全部 false 权限字段和输出文件集合。成功状态只能是
`VERIFIED_NO_REPLAY`，不表示诊断或完整 provenance 成功。
`run_process` 默认仍用 DEVNULL；仅显式 handshake 启用 stdin 管道，不改变准备构建路径。

监督保持原600秒构建、120秒JVM、180秒父监督和900秒总命令上限；调用者共享同一
OutputBudget 和绝对总 deadline。父阶段预留3秒供最多2秒进程组清理及返回，
预检、认证、退出后复核不重置时钟，JVM阶段含退出后复核亦限120秒。
认证工作放 daemon thread；慢工作不阻止排空管道/杀进程，不能访问 stdin 或写产物。
核验间接调用的Git inventory由同一绝对deadline/取消token约束，独立进程组在finally清理；
过期后拒绝继续启动Git。监督退出时取消worker并最多等待1秒，Git清理最多0.5秒。
外层预检与进程监督共享取消状态，交接时收到的信号不能丢失或释放ACK。
Python不能强制中断这些只读线程：超时后可能仍在读取，不能宣称所有父工作已被强制停止。
这与既有进程观察一样只监督合作进程，不抵御恶意setsid逃逸或内存字节码替换。

普通测试仅含人工证据与短 Python 子进程，覆盖 ACK/EOF、慢认证、提前退出、拒绝、
输出洪泛、退出后慢复核、非零退出、receipt/READY/verified漂移。
Linux JUnit 强制19项Python测试零跳过，另从当前编译产物启动真实普通JVM进行
verify-only READY/实际proc/loaded-class/ACK/EOF联调；该测试明确mock prepare receipt，
不能代替实际干净构建认证。Windows跳过真实Linux进程测试，不当作Linux成功。

本次没有历史输入读取、22步回放、collect入口或唯一session登记。
网络隔离、pipe+artifact累计10MiB、真实journal检查器和整体监督仍待集成；
本次新增修改：启动前从`budget.quota`永久预留2MiB给READY/verified两文件，
与每文件既有1MiB ceiling一致；显式更小额度仅可收紧，不得扩张。
余额不足在启动前拒绝，未用预留不回收。Java实际writer在CREATE_NEW前按UTF-8
序列化后字节扣费，I/O失败不返还；argv、READY和verified绑定同一额度，
退出后核对两文件合计。读取不重复计费，stdout/stderr仍消耗根额度剩余部分。
这不约束Maven classpath/HOME/TMP、journal等尚未接线writer，不能当整体session quota。
最终整体独立Review/适用CI、兼容性与inputhash刷新及唯一登记之前不得执行历史诊断。
#140/GoalWindow停止，#95/#96/reserved/provider/runtime不解锁。
生产、构建、workflow、selector、原guard和冻结协议均不变。
架构文档影响仅为研究入口，无需改README/AGENTS/ADR。

## 本次新增修改：可信宿主的选定命令与来源连续性

`FixedWitnessWriterHost.launchAndObserve(RetainedCommand)` 现在把调用方独立保留的
argv、cwd、显式环境和文件 SHA-256 接到原启动路径；receipt 对集合做不可变复制，
不再保留调用方可变的 `ProcessBuilder`。限定绝对 interpreter/入口文件、`-I -B`、
唯一 session 参数；环境先清空，只接受 HOME/TMPDIR/LANG/LC_ALL/TZ，不继承 PATH、
PYTHONPATH 或其他宿主变量。缺失/非 canonical 文件、哈希漂移、cwd/session 不符
均拒绝。所选文件在启动前、原 PID 绑定 grant 前和直接子进程 terminal 后复核。
所有读取在原有界 worker 中、逐块检查同一宿主 deadline，保留原3秒 cleanup 余量；
不新增任务、计时窗口、重试或证据额度。失败永久消费本次启动，保留 INCOMPLETE，
已启动子进程仍由原 kill/terminal 路径处理，不能写成功后缀。

9项回归包含来源在三处边界漂移、依赖消失、外部集合修改、环境/命令拒绝、外来
session 和原 deadline。Linux 用例以实际隔离 Python 命令、Java grant pipe、既有
`HostWriterHandoff.prepare -> TracerSupervisor` 及 fake kernel 走到清理/reap 和
计费日志；Windows 跳过该联调，不当作 Linux 成功。其他测试仅注入 Process。

这仅认证调用方选定且独立保留的字节；调用方仍须冻结完整 source/toolchain/manifest
与导入来源。Python stdlib、动态库、已缓存模块、完整任务/fixture 调度并未由本
receipt 自动认证；合作式复核不抵御并发 change-and-restore 或内存替换。原
`ProcessBuilder`/`Callable` 入口保留为既有组件和合成测试路径，不能作为来源证明。
不会在未计时宿主内执行 checkout、build、identity 子进程或能力探针。

有限剩余集成项仍为完整可信宿主/冻结调度、coordinator ALL-artifact/JVM 的同根互斥
额度传输与实际 writer、完整 filter/external no-injection checker、未覆盖的复杂
cleanup/job-control、冻结 fixtures/task 表及 EXITKILL 负例，随后满足整体最终
Review/CI、compatibility/inputhash 和唯一登记。整体原生/network clean-build/历史
验收尚未完成，研究结果和用户可见功能本轮无新增；170A/174 接受及原停止门不变。
仅 test-only 入口接线，生产架构/README/AGENTS 和冻结协议/预算/guard 无需改动。

## 本次新增修改：prepared 仓库 Python 来源接入原监督生命周期

`HostWriterHandoff.prepare -> TracerSupervisor.for_host -> PreparedProbeSources`
现在从独立保留的 prepare manifest 选取 `scripts/(test_)?fixed_witness*.py`，
逐文件认证原 SHA-256，并要求 probe、launcher、supervisor、writer handoff 与
coordinator artifact 五个来源根齐备。对已认证字节仅做 AST 静态检查，不 import
或执行代码；顶层和函数内的仓库同名前缀 import 都必须存在于原 inventory，
相对 import 拒绝，循环 import 不会递归执行或重新冻结当前文件。
所有选定文件的原 digest 接入已有启动前和 reap 后复核；漂移沿用共享失败锁存、
原 deadline、ABI 撤销和 INCOMPLETE 证据，不能 fork 或生成成功观察。

28项人工回归（新增7项）覆盖 deferred import 缺项、每个必要根缺项、循环与间接
依赖、不执行来源字节、实际 supervisor 启动前拒绝与真实模拟 reap 后证据拒绝，
并以当前仓库 Python 源码验证静态清单闭合。全部 native/file transport 注入；
没有执行能力探针、clean build、network 套件或历史输入。Linux JUnit 强制这些
回归零跳过，Windows 的该桥接仍为平台 skip，不宣称 Linux 能力通过。

这是 prepared 仓库来源连续性，不是完整 Python 导入认证：stdlib、动态库、缓存
模块实际 origin、动态 import/字符串代码、工具链和完整 suite/task 调度仍须由
可信 host 冻结。当前仍没有完整 manual CLI；prepare receipt 的产生必须保留在
受跟踪协调器内，不能为提前拿到 manifest 而移到未计费宿主。此先后接线仍待完成。
其余有限剩余项为完整 filter/external no-injection checker、复杂 cleanup/job-control、
冻结 fixtures/task 表及 EXITKILL 负例，随后整体最终 Review/CI、compatibility/inputhash
与唯一登记。整体原生/network clean-build/历史验收尚未完成；研究和用户功能无新增。
170A/174 的接受及原执行停止门不变。此次仅研究实现，无生产架构文档改动。
