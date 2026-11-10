# 任务监督：EXEC 入口与事件匹配

本次新增修改接续 #211，实施已接受的 #174。`CreationSyscallDispatcher.exec_event`
只匹配调用方已经认证的 EXEC stop 与 GETEVENTMSG(old TID)，不认证原始 wait 来源。
只接受该旧 TID 精确绑定 birth 尚未完成的 native x86-64 execve(59) 或 execveat(322)
入口；新 TID 必须是同组存活 binding 的原 TGID。未知、跨进程、失败后无重试入口、
newborn、creation、重复事件、停止账本和非法类型全部失败关闭。

Linux v6.17 [fs/exec.c](https://github.com/torvalds/linux/blob/v6.17/fs/exec.c#L1690)
在 load_binary 前保存 old_vpid，成功后在 1725 行发送 EXEC(old_vpid)；该值取自父进程的
PID namespace。本组件要求 tracer 与 tracee 同 PID namespace，不能推广到 namespace
映射。native syscall 编号来自该版本的
[syscall_64.tbl](https://github.com/torvalds/linux/blob/v6.17/arch/x86/entry/syscalls/syscall_64.tbl)。

返回不可变 `ExecEventFacts`，保留旧入口事实；登记后拒绝该 birth 的后续 syscall
或重复 EXEC，直到同一 owner 完成 `install_exec_identity`。

本次新增修改：`PostHandshakeDispatcher` 在共享计费、原 owner/deadline 检查后，通过
TGID 旧 binding 的 wire 只读一次 GETEVENTMSG，再匹配 old TID 的执行 birth。两步之间
不 wait/resume。安装调用账本 `exec_identity`，撤销被内核替换的当前 leader binding，
保留 executor 自己的 wire、入口和原 group birth，将 TID 改成 TGID；随后只用 executor
wire 在原期限内 SYSCALL resume。旧 leader 的 wire/token 不复制到执行 birth。
迁移后下一 syscall 必须是实际 native、成功且返回 0 的 EXIT，之后才能接收新 ENTRY；
重复事件、compat exit、未完成的组内 creation 或尚未实际 reap 的其他 siblings 均停止。
账本区分 exec-replaced 与实际 reaped；尝试预算不退，只有被内核撤销的 leader live
credit 按模型释放。若安装后的期限检查或 resume 失败，保留新 birth/TGID binding
供未来全树清理使用；`cleanup_tid` 历史数值仍不能独立授权 kill。

`BoundedWaitOwner` 下一轮使用新 bindings。仅在已观察 native exec ENTRY 的非 leader
旧 TID 返回确切 ECHILD、且同组 live TGID binding 仍存在时，继续有界轮询 TGID，
不将错误当作死亡/迁移凭据、不退额度，也不增加 deadline。缺少实际 EXEC 事件时仍
按原期限失败；普通 ECHILD/其他错误立即失败。该窗口来自 Linux v6.17
[de_thread](https://github.com/torvalds/linux/blob/v6.17/fs/exec.c#L995)的 TID 交换和
旧 leader 静默释放。ptrace 手册的
[execve 小节](https://man7.org/linux/man-pages/man2/ptrace.2.html)说明 TGID 下报告 EXEC，
SYSCALL resume 后报告成功零 EXIT。事件来源仍依赖专属实际 wait owner 与同 PID
namespace 前提；synthetic 不能证明这些原生前提。

8 项新增 synthetic 测试覆盖两种 exec 入口、leader/nonleader、旧 birth 与独立 wire、
错误入口/组/类型、失败后重试、重复事件/后续 syscall、停止预算与 reap。
Linux Wrapper bridge 要求完整 43 项 dispatcher 测试无 skip。本次另补 9 项 post-handshake
行为测试（共 32 项）和 3 项 targeted wait 测试（共 22 项），覆盖迁移/后续退出、重复、
TID 复用、wire 保留、预算/期限、未完成 creation、compat、失败保留 cleanup identity
以及旧 TID ECHILD 等待实际 TGID 事件。未执行真实 fork/ptrace、
能力探针、network/clean-build 验收或历史 22 步。

EXEC 停止接线已可通过上述保守转换继续 resume；它不提供完整监督器或验收能力。
仍需初始握手有界等待、
signal/vfork、全树清理、冻结 ABI、可信宿主/writer 与完整 fixture；原整体最终
Review/CI、兼容性/inputhash/唯一登记和 #140/GoalWindow/#95/#96/runtime 停止门不变。
生产/构建/workflow/冻结协议不变，README/AGENTS 架构无需调整。
