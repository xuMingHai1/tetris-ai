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

每个 birth 保存 ticket/TID/wire 绑定，拒绝错配及创建结果到达前的新入口。
vfork 子可在父结果之前 reap。终态 reap 清除该 birth 的独占 wire，
包含未完成的 clone3 token；数值 TID 复用采用全新 wire，不能继承旧 token。
错误锁存后不再处理 syscall/event，只有真实终态 reap 可以继续用于全树清理。
成功 require_drained 仅表示本组件账本清空，不是 COMPLETE。

## 验证与限制

13 项合成测试调用生产 PtraceAccess 的真实 wire 编解码和生命周期，注入 fake ptrace，
覆盖计费与 register 干预顺序、耗尽拒绝、错误锁存、事件/结果错配、vfork、
出生身份错误、native ABI 拒绝和带未完成 token 的 TID 复用。
FixedWitnessCreationDispatchTest 将它们接入普通 Linux Wrapper CI。
测试不加载 libc，不启动 tracee、探针、网络验收或历史输入。

这仍不是完整或已验证的监督器：真实 wait-stop 的预算计费、停止/信号认证、
coordinator 可信准入、线程 exec/TID 重绑定、TRACEME/options/EXITKILL、
宿主/writer 所有权与固定 fixture/task 预留表尚需整体集成。
本组件只处理 native 创建义务，非创建 syscall 不维护完整入口/出口状态机；
冻结的非创建 ABI 探针例外须由外层专门认证，不可由此 native 路径绕过。
syscall_stop 返回不授予独立 resume 权限；外层必须先完成全部监督检查。

整体最终 Review/CI、兼容性/inputhash 与唯一登记前不得启动真实套件或历史输入。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default 停止门保持。
本次仅四个新增 test-only 文件；生产结构、依赖和运行方式不变，README/AGENTS 无需调整。
