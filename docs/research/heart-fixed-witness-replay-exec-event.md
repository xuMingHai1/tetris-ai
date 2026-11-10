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

返回不可变 `ExecEventFacts`，保留旧入口事实；登记后拒绝该 birth 的任何后续 syscall
或重复 EXEC。它不迁移 ledger、不撤销 leader、不改 wire/TID、不读内核、不 resume；
预算、旧 bindings 和全部 cleanup 身份保持。它也不解决其他 sibling 的终态观察顺序。
旧 binding 的 reap API 仅作模型清理，不能拿旧 TID 去清理实际已迁移的内核任务。
PostHandshakeDispatcher 仍拒绝 EXEC，未接入本组件；不能据此声称 native 认证已经完成。

8 项新增 synthetic 测试覆盖两种 exec 入口、leader/nonleader、旧 birth 与独立 wire、
错误入口/组/类型、失败后重试、重复事件/后续 syscall、停止预算与 reap。
Linux Wrapper bridge 要求完整 43 项 dispatcher 测试无 skip。未执行真实 fork/ptrace、
能力探针、network/clean-build 验收或历史 22 步。

下一步把 EXEC 的原始 stop/GETEVENTMSG 认证、账本迁移、wire/sequence rekey、targeted
wait 与 cleanup 身份作为一致转换接通，再开放 resume。仍需初始握手有界等待、
signal/vfork、全树清理、冻结 ABI、可信宿主/writer 与完整 fixture；原整体最终
Review/CI、兼容性/inputhash/唯一登记和 #140/GoalWindow/#95/#96/runtime 停止门不变。
生产/构建/workflow/冻结协议不变，README/AGENTS 架构无需调整。
