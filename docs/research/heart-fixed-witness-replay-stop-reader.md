# Fixed-witness wait-stop 读取组件

#170A 与 #174 的具体政策已由用户明确接受。本组件落实 #174 的所有 wait-stop
共享计费及按内核事实读取要求；不是完整监督器，也不是执行许可。

`StopReader.read` 接收唯一 tracer 所获的一次真实 waitpid 结果，在严格解码或
ptrace 读取之前，按原始 stop marker 调用 `TaskLedger.wait_stop`。未知事件、
畸形 stop word 和非法 TID 也不能绕过该计费。额度到达上限后不发起内核读取。
syscall stop 读取 `GET_SYSCALL_INFO`，不靠奇偶猜测入口/出口；事件读取
`GETEVENTMSG`；signal stop 读取匹配的 `GETSIGINFO`，失败不猜测为可恢复信号。
任何异常锁存停止，不自动重试。terminal word 不读取 ptrace，也不自行释放 live。

返回的冻结事实不认证 birth/TID、事件来源、ABI 例外或 signal reinjection，
更不允许 resume。调用方必须恰好交付一次真实 wait 结果，并结合创建调用层、
exec 重绑定与握手状态验证；reap/全树清理由其独立完成。未知 ABI 的固定非创建
探针例外仍待最终 dispatcher 绑定，读取层不提前吞掉该事实。

13 项纯合成测试验证计费顺序、预算失败、错误锁存、连续出口事实、ABI事实保留、
四种事件、signal 与 terminal 分离。普通 Linux Java 桥接实际运行这些测试；
不启动 tracer、能力探针、网络/clean-build 人工验收或历史 22 步输入。

整体最终 Review/CI、完整 fixture/task 预留表、兼容性/inputhash 与唯一登记仍是
原始执行前置。900 秒、10 MiB、512/128/2,000,000 及其他冻结停止门保持。
