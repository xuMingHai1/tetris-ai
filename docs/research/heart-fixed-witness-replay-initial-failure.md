# 初始 wait 的首个失败原因保留

本次新增修改：`InitialWaitOwner.run` 的异常收尾此前总是覆盖
`CoordinatorLaunch.stopped`，使已有取消、证据额度失败或 admission 失败退化为
通用 wait 错误。现在仅在 session 尚无失败时写入通用原因；owner 自己的
`stopped` 仍记录 wait 上下文，原异常继续抛出。已有失败仍阻止等待与恢复，
不改变 attempt/live/stop 计费、cleanup 身份、原 deadline 或一次性约束。

四项 fake-only 回归中，修复前有三项因原原因被覆盖而失败；修复后全部通过。
覆盖进入 run 前已有失败、wait 内取消、真实返回的错误目标 stop 在 admission
失败后保留原因，以及无先前失败时 wait 异常仍锁存 session。Linux JUnit 桥
要求四项全部执行、30秒内通过且零 skip；Windows 仅编译该桥并平台跳过。

该修复独立于 #233 的终态 PID 隔离，不提供未握手 pidfd/kill 权限，亦不代表
完整失败清理、监督器或验收通过。#170A/#174 已明确接受的政策、所有预算及
整体执行门保持；未执行 native fork/wait/ptrace/pidfd/signal/probe、网络
clean-build 或历史22步。生产API、构建配置、冻结协议和运行方式不变，
README/AGENTS 无需同步。
