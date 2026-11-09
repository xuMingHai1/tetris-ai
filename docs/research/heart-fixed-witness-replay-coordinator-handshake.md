# 协调器首次停止握手实施组件

本组件实现已接受 #174 的一个接线步骤，复用 #200 StopReader 和 #201
CreationSyscallDispatcher。#170A 与 #174 具体修订已于 2026-11-09 获用户明确接受；
本实施没有改变 512 次累计尝试、live128、2,000,000 wait-stop、原时限或证据 10MiB。

`CoordinatorHandshake.accept` 仅消费可信初始 fork 的一次真实 waitpid 结果。
调用者必须已在 fork 前预留协调器、绑定返回 PID/birth，并保证子仍位于可信的
TRACEME/self-SIGSTOP pre-exec stub。组件不 fork、不 wait、不读 /proc，也不独立
证明来源或出生时间。reader 与 dispatcher 必须共享同一 ledger。

顺序为：共享 wait-stop 计费 → 真实 GETSIGINFO → 核对 SIGSTOP、零 errno、
SI_USER/SI_TKILL、自身发送 PID 与预期 UID → dispatcher 一次性 birth/握手准入 →
固定 SETOPTIONS → PTRACE_SYSCALL(signal=0)。只有这个可信自发 SIGSTOP 被抑制；
没有新增通用信号重注入规则。错误 TID 仍经 StopReader 计费，但禁止任何 ptrace 请求。
任何失败要求整树清理，禁止重试；resume 返回错误也不能假定目标仍停着而再次放行。

本次新增修改还扩展 `SignalInfo`：仅对 SI_USER/SI_TKILL 读取 Linux x86-64
siginfo offset16 的有符号 PID/无符号 UID；其他 si_code 不解释该 union。
13 项合成回归覆盖顺序、共享预算、身份与信号拒绝、错误 latch、重复握手、
不确定 resume、真实 wire 布局和一次性初始 syscall 出口。普通 Linux JUnit 桥接
实际执行该 Python 套件；Windows 平台跳过不算 Linux 验证。

这是 test-only 组件，不是完整监督器或实际能力验收。尚须实现可信 TRACEME 子 stub、
整体 wait/resume 调度、exec/TID/线程组出生身份迁移、冻结 ABI 探针、宿主/writer
传输与完整 fixture/task 表。SETOPTIONS 成功不证明 EXITKILL。
最终整体 Review/CI、兼容性/inputhash 和唯一登记前，不执行真实探针、
network/clean-build 人工套件或历史 22 步；其他冻结停止门保持。
生产架构、玩法与依赖不变，README/AGENTS 无需修改。
