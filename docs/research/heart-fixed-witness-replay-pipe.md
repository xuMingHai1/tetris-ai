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
Linux JUnit 强制16项Python测试零跳过，另从当前编译产物启动真实普通JVM进行
verify-only READY/实际proc/loaded-class/ACK/EOF联调；该测试明确mock prepare receipt，
不能代替实际干净构建认证。Windows跳过真实Linux进程测试，不当作Linux成功。

本次没有历史输入读取、22步回放、collect入口或唯一session登记。
网络隔离、pipe+artifact累计10MiB、真实journal检查器和整体监督仍待集成；
本组件只累计pipe输出，产物读取本身限1MiB不代表累计产物配额。
最终整体独立Review/适用CI、兼容性与inputhash刷新及唯一登记之前不得执行历史诊断。
#140/GoalWindow停止，#95/#96/reserved/provider/runtime不解锁。
生产、构建、workflow、selector、原guard和冻结协议均不变。
架构文档影响仅为研究入口，无需改README/AGENTS/ADR。
