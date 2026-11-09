# 已接受任务政策：创建生命周期组件

本次新增修改接续已接受的 [#174 具体修订](heart-fixed-witness-replay-task-budget-proposal.md)、
[#192 任务账本](https://github.com/xuMingHai1/tetris-ai/pull/192) 和
[#193 创建入口仲裁](https://github.com/xuMingHai1/tetris-ai/pull/193)。
这是 test-only 实现，不是新研究假设，不新增预算或执行授权。

`scripts/fixed_witness_replay_lifecycle.py` 的 `CreationLifecycle` 将
`CreationPolicy.arbitrate` 返回的预留义务按父 birth ticket 保存，再匹配
FORK/VFORK/CLONE 子事件、syscall-info 结果和终态 reap。legacy clone 的
事件类型由原仲裁组件决定，不再从 syscall 名字猜测。成功返回值必须与子事件
TID 相同；内核失败仅释放 live 预留，不退累计尝试。vfork 子可先 reap，
随后才处理父 syscall 出口。clone3 不接受子事件，出口只能是 Linux `-ENOSYS`。

未知、重复或错配的事件和结果会锁存失败，后续 entry 不再返回放行义务。
已在途的匹配事件、结果和真实终态 reap 仍可结账供全树清理；清理后也不能
通过 `require_drained` 转成成功。父在创建中被杀时调用原账本的 pending
清理，已绑定的子仍持有自己的 live 槽，不因父退出被抹去。

12 项纯合成序列测试覆盖事件分类、vfork 时序、错配/重复、失败计费、
clone3 固定 errno、返回值类型/范围、父清理、TID 复用和失败锁存。
`FixedWitnessCreationLifecycleTest` 在普通 Linux Wrapper CI 中执行这些测试。
它们没有 ptrace、native syscall、tracee、网络或历史输入。

**尚未实现内核事件认证或完整监督器。** 调用方必须从真实 syscall-info/
wait-stop 绑定父 birth ticket；clone3 必须先由 wire adapter 完成一次性
suppression token，再向本组件报告出口。传入 `-38` 本身不证明已注入 ENOSYS。
本组件不提供 resume API，也不为原始信号或非创建 syscall 建立完整状态机。
线程 exec/TID 重绑定、TRACEME/options/EXITKILL、ABI 分发、出生时间认证、
宿主和 writer 集成及固定 fixture/task 预留表仍须完成；整体最终 Review/CI、
兼容性/inputhash 与唯一登记前不得运行真实探针、全链验收或历史输入。
#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/runtime/default 停止门保持。

本次仅新增组件、合成测试、Linux 桥接与此说明，避开并行 ptrace wire 和 writer
分区的文件。不改变生产架构、运行方式或依赖，README/AGENTS 无须调整。
