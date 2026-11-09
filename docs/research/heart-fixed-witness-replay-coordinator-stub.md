# 协调器可信子 stub 实施组件

本组件接续 #202 的父侧首次握手，实现已接受 #174 的子侧启动顺序。
#170A 和 #174 具体修订均已获 2026-11-09 用户明确接受；512 次累计创建尝试、
live128、2,000,000 wait-stop、原时限及证据 10MiB 保持。

`CoordinatorStub` 在专用单线程 tracer 的唯一协调器 fork **之前**准备：复制并编码
绝对 executable、argv、绝对 cwd、显式 environment，预先加载原生 transport。
它不 fork，不预留或返还账本额度。调用者仍须先完成协调器预留，设置固定描述符，
保留原 deadline/清理所有权，并仅在真实 fork 子分支调用 `run()`。
父分支必须将实际返回的 PID/birth 绑定到账本与 dispatcher，不能伪造出生证据。

子侧顺序为：核对自身/父 PID → TRACEME → 自发 SIGSTOP → 恢复后复核父 PID →
chdir → execve。没有 shell、PATH 查找、隐式环境继承或任意用户 callback。
父侧继续使用 `CoordinatorHandshake`，必须在首次 resume 前验证信号和 birth、
安装完整固定 options。子侧从 SIGSTOP 返回不能独立证明放行者或 options，
PID 检查也不代替出生身份、线程组证据或 EXITKILL 能力验证。
任意异常（包括 SystemExit/KeyboardInterrupt）或 execve 意外返回都直接
`_exit(125)`，不运行退出 hook、不打印 traceback，不向未计费的继承流输出。
失败不重试，不进入其他命令；父仍须实际 reap，不能仅凭退出码退 live 槽。

本次新增修改为 `PtraceAccess.trace_me`、新子 stub、12 项合成回归与 Linux JUnit
桥接。测试注入所有子进程/内核操作，不 fork、不加载 libc、不停止或 exec 任务。
普通 Linux suite 的桥接实际执行 Python；Windows skip 不是 Linux 验证。
本组件不认证 executable、关闭描述符或安装 seccomp，这些仍由完整可信调用路径负责。

尚缺可信预留/fork 接线、完整 wait/resume loop、exec/TID/线程组出生迁移、ABI 冻结
探针身份、清理、宿主/writer 传输和完整 fixture/task 表。整体最终独立 Review/CI、
兼容性/inputhash 及唯一登记之前，不执行真实探针、完整 network/clean-build 人工
验收或历史 22 步。#183/#189 已关闭预算、#140 BOUNDED_MISS、GoalWindow、#95/#96、
reserved/provider/runtime/default 停止门保持。生产结构、玩法、依赖不变；README/AGENTS
无需修改。
