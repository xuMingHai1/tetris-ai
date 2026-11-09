# 协调器预留与启动接线组件

本次新增修改接续 #202/#203：`CoordinatorLaunch.launch` 在原绝对 deadline 内，
核对 `/proc/self/task` 只有 tracer leader、当前 PID 与账本 tracer 身份一致，检查
三个固定标准流 source FD 可用，再复核身份和 deadline，预留 coordinator 后只调用
一次直接 `fork`。这不是新的研究执行入口，没有 CLI 或 wait loop。
#170A/#174 的明确接受保持：累计 512 次含失败和线程、live128、2,000,000 wait-stop、
原时限及证据 10MiB 不变，不新增真实探针或历史输入执行权限。

子分支将三个事先准备且编号均 >=3 的 FD 映射到 0/1/2，再关闭所有更高 FD，
然后进入既有 `CoordinatorStub`；source FD 不会在顺序 dup2 中互相覆盖。
任何设置失败直接 `_exit(125)`，不进入 stub、不打印异常或执行退出 hook。
父分支不关闭调用者的 FD。调用者必须保证输入来源及 stdout/stderr 对应共享证据
quota 下的已计费 sink；本组件不认证 sink、建立 writer 传输或提供构建磁盘硬上限。

父分支立即保留实际 fork PID 为 `cleanup_tid`，然后登记出生、结算成功 fork、建立
独占 coordinator wire 的 dispatcher 与首次握手。后续出生使用独立 wire factory，
不共享 clone3 token。fork 的 OSError 只归还 live 预留、不退累计尝试；初始化失败
仍保留清理身份和 live 占用，不能把异常当成 reap。`TaskLedger.require_tracer`
只检查既有 tracer 绑定，OS 身份和单线程核验仍由 launcher 完成。

`accept_initial_wait` 只接收外部实际取得的一次 wait 结果，复核原 deadline/owner，
交给 #202 的共享计费、信号认证、options-before-resume。过期或 owner 不符的 stop
也先计费，禁止任何 task 访问；失败和不确定 resume 都不重试。调用者仍须负责
有界 wait、全树 kill/reap、清理预留和原总截止；`cleanup_tid` 不是出生时间证明，
不应对已 reap/复用 PID 发信号。启动和握手成功都不是监督器 COMPLETE 证明。

18 项合成回归覆盖创建预算、fork 失败不退款、身份/单线程、FD 顺序与失败退出、
初始化失败保留身份、deadline、wait-stop 耗尽及一次性握手；Linux 常规 JUnit 桥接
执行实际 Python 测试。所有 fork/FD/子进程/ptrace 操作均注入，不加载 native wire，
Windows bridge skip 不是 Linux 验证。未运行真实 coordinator、探针或网络验收。

剩余：认证 wait/resume 全循环、newborn stop、exec/TID/线程组出生迁移、ABI 冻结
探针身份、signal/vfork/全树清理、宿主/writer 传输与完整 fixture/task 预留表。
整体最终独立 Review、适用 CI、兼容性/inputhash 和唯一登记之后才可实际执行。
#183/#189 关闭预算、#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/
runtime/default 停止门保持。生产玩法/结构/依赖不变，README/AGENTS 无需更新。
