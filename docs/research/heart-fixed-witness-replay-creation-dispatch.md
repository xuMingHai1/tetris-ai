# 已接受任务政策：创建 syscall 调度组合

本次新增修改接续已接受的 #170A / [#174 具体修订](heart-fixed-witness-replay-task-budget-proposal.md)。
存储分离、任务账本、创建仲裁、生命周期与 ptrace wire 已分别交付；
这里组合已有实现，不新增研究预算或真实执行许可。

## 组合职责

scripts/fixed_witness_replay_dispatch.py 的 CreationSyscallDispatcher 从每个 birth
独占的 PtraceAccess 读取 syscall-info。native 创建入口先调用 CreationLifecycle.entry，
完成累计尝试/live 预留后才调用 suppress_clone3；出口先完成 wire 的一次性 token 和
真实 register/ENOSYS 核验，再结算生命周期。FORK/VFORK/CLONE 采用 wait decoder 的大写
事件名，在本边界转换为生命周期事件；子 TID 必须由外层 GETEVENTMSG 认证。

每个 birth 保存 ticket/TID/wire 绑定，拒绝错配及任何 syscall 结果到达前的新入口。
vfork 子可在父结果之前 reap。终态 reap 清除该 birth 的独占 wire，
包含未完成的 clone3 token；数值 TID 复用采用全新 wire，不能继承旧 token。
错误锁存后不再处理 syscall/event，只有真实终态 reap 可以继续用于全树清理。
成功 require_drained 仅表示本组件账本清空，不是 COMPLETE。

## 验证与限制

26 项合成测试调用生产 PtraceAccess 的真实 wire 编解码和生命周期，注入 fake ptrace，
覆盖计费与 register 干预顺序、耗尽拒绝、错误锁存、事件/结果错配、vfork、
出生身份错误、native ABI 拒绝和带未完成 token 的 TID 复用。
FixedWitnessCreationDispatchTest 将它们接入普通 Linux Wrapper CI。
测试不加载 libc，不启动 tracee、探针、网络验收或历史输入。

这仍不是完整或已验证的监督器：真实 wait-stop 的预算计费、停止/信号认证、
coordinator 可信准入、线程 exec/TID 重绑定、TRACEME/options/EXITKILL、
宿主/writer 所有权与固定 fixture/task 预留表尚需整体集成。
本次新增修改：native 创建及非创建 syscall 均保存内核已报告的入口状态，
拒绝重复入口和无入口出口，不靠奇偶位推断 operation。coordinator 通常从入口开始；
外层认证 TRACEME/SIGSTOP 握手后，必须在任何 syscall 处理前显式调用
coordinator_signal_stop，记录一次性初始握手准入，随后等待首次 syscall entry。
该方法不认证信号、不安装 options、不恢复任务；错误或重复调用锁存失败，
不能在普通信号停点重复准入，也不能用于子 birth。
原 #201 synthetic 曾要求新生 birth 的成功零创建出口；本次接纳集成已依据 Linux
源码修正，见下节。旧测试通过不证明该时序正确。
退出或被杀时允许直接 reap，包括未完成的 syscall；它只清理状态，不伪造成功出口。
受管 task 的原生 ptrace（101）入口直接锁存失败，不改写返回值、不恢复执行。
新增 11 项回归覆盖这些拒绝路径、一次性出生/协调器握手例外及清理，并更新旧 TID 复用场景。
这些 synthetic 状态测试不证明宿主内核的新生停点时序或监督能力。
线程 exec/TID 变化仍不支持，外层不得把该路径接入完整验收或猜测重绑定；
冻结的非创建 ABI 探针例外须由外层专门认证，不可由此 native 路径绕过。
syscall_stop 返回不授予独立 resume 权限；外层必须先完成全部监督检查。

整体最终 Review/CI、兼容性/inputhash 与唯一登记前不得启动真实套件或历史输入。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default 停止门保持。
原组合交付仅四个新增 test-only 文件；生产结构、依赖和运行方式不变，README/AGENTS 无需调整。

## 本次新增修改：共享 syscall-info

接续 #204 的待集成项，`syscall_info_stop(ticket, tid, info)` 接收 StopReader
已读出的不可变 `StopFacts.syscall`。原 `syscall_stop` 保留独立读取入口并复用同一策略。
clone3 的 `suppress_clone3_from_info` / `finish_clone3_from_info` 复用相同入口/出口事实，
仍执行 native ABI、实时 register、固定 ENOSYS 与单次 token 核验；原独立 wire 方法也保留。
每个 clone3 入口/出口现在各只读取一次 GET_SYSCALL_INFO，调度层不额外计 wait-stop。

这不是可传递的认证凭据：可信单线程 owner 仍须先完成 wait 计费、birth/TID/当前停点认证，
确保传入本 birth wire 在当前停点读取的事实，并在消费完毕前不恢复任务。
本 API 无法判断调用方伪造或跨停点传入的数据；不得因此跳过外层认证或宣称完整监督器已验证。
错误身份、非 SyscallInfo、重复入口仍锁存失败；实时寄存器错配不能被共享事实掩盖。

新增 7 项 synthetic 回归使创建调度 suite 共 33 项：覆盖真实 StopReader/wire/ledger 组合的
精确读取与计费次数、ABI/attempt/wait-stop 拒绝、实时寄存器错配、错误 birth/输入与重复消费。
Linux Wrapper bridge 同步要求全部 33 项通过且无 skip。没有实际 fork、ptrace、探针、网络、
clean-build 验收或历史输入执行。整体 wait/resume、newborn/exec 认证和原执行门仍未完成。

## 本次新增修改：newborn 初始 stop 后等待 entry

`newborn_signal_stop(ticket, tid)` 仅在外层认证已绑定 birth 的初始 SIGSTOP 后调用，
一次性将 newborn 状态转换为等待首次 entry。拒绝 coordinator、错配或重复调用。
认证前的任何 syscall-info（包括 zero exit）均拒绝；认证后无入口出口继续拒绝。
选项/恢复仍由外层负责，方法成功不是恢复许可。原因与 Linux v6.17 源码链接见
[停点组合说明](heart-fixed-witness-replay-stop-dispatch.md#本次新增修改已绑定-newborn-的首次停止)。
本次替换旧 zero-exit 测试并补充一次性/身份回归，suite 共 35 项，Linux bridge 同步。
原 #207 保留的 coordinator 自发停止零出口假设已在下述增量修正；既有预算及整体执行门保持。

## 本次新增修改：coordinator 自发停止后也等待 entry

`coordinator_signal_stop` 不再建立 `coordinator-handshake` 零出口例外。
可信初始 self-SIGSTOP 经外层认证后，仍只允许一次准入；之后接受首次 native
ENTRY，并要求相应 EXIT。任何没有入口的 EXIT（包括成功零返回）都锁存失败，
不仲裁创建、不写寄存器、不恢复；terminal reap 清理路径保持。

源码链与限制见[协调器握手说明](heart-fixed-witness-replay-coordinator-handshake.md#本次新增修改首次-syscall-必须是-entry)。
本次修复原 #201/#202 synthetic 时序假设，不能把这些历史测试通过当作实际能力证明。
同步创建调度 35 项、握手 13 项与停点组合 23 项合成测试及 Linux bridge 计数；
移除组合测试的虚构 `handshake_exit()` 前置，deadline/uncertain-resume 测试以合法
首次 ENTRY 到达各自失败分支。生产游戏及预算、原整体执行门不变。
