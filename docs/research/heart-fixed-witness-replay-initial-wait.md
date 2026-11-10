# Coordinator 初始有界等待组件

本次新增修改：`InitialWaitOwner` 对已经由 `CoordinatorLaunch` 启动并登记的
coordinator，独占消费其实际首次 wait。只以实际 PID 调用
`waitpid(WNOHANG | __WALL | __WNOTHREAD)`，不使用 `wait(-1)`，不重新 fork。
idle/EINTR 最多睡眠 10ms 且不超过原 deadline 剩余时间；EINTR 不计 stop，
不创建新预算。其他 wait 错误、错误身份、非可信初始信号、超时与不确定 resume
均停止，不重试、不归还未确认 reap 的 live credit，保留 cleanup 身份。

实际 stop 进入 `accept_initial_wait`，先共享计费再认证；wait 跨越 deadline 或
owner 变化时仍计费，但不访问 task。`CoordinatorHandshake` 接受 launcher 的
owner/deadline check，在读取 siginfo 后、SETOPTIONS 前和 SYSCALL resume 前再次
检查，修复此前操作跨越截止后仍可首次 resume 的缺口。独立 handshake 模型的
调用者仍负责自己的环境核验；真实 launcher 总是传入核验函数。
成功只交付独占的 `PostHandshakeDispatcher`，后续由已有 `BoundedWaitOwner`
接管；这不是 watchdog、全树 kill/reap、来源/输出认证或 COMPLETE 凭据。

13 项 synthetic 回归覆盖真实 tuple 路由、一次性转交、独占、idle/EINTR、剩余时间、
跨截止 stop 计费、错误身份、terminal-before-handshake、wait 错误、错误 tuple、
siginfo/options 期间超时、owner 变化和 uncertain resume。所有 fork/wait/ptrace
均为注入 fake；Linux JUnit bridge 在常规 Wrapper suite 实际运行，Windows skip
不能作为 Linux 验证。不执行 native probe、网络 clean-build 或历史 22 步。

#170A 存储分离和 #174 累计512次尝试/live128/2,000,000 wait-stop 的明确接受不变，
原时限、证据10MiB、失败停止和不退款语义不变。仍需 ordinary signal/vfork policy、
全树清理、冻结ABI探针身份、宿主/writer 与 fixture/task 预留表；整体最终 Review、
适用 CI、兼容性/inputhash 和唯一登记后才能实际执行原验收。
#183/#189关闭预算、#140 BOUNDED_MISS、GoalWindow、#95/#96、reserved/provider/
runtime/default 停止门保持。生产代码、依赖和运行方式未改，无需修改 README/AGENTS。
