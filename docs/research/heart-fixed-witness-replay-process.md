# 固定动作回放：Linux 进程监督组件

接续 [逐步证据组件](heart-fixed-witness-replay-evidence.md) 和 [已接受设计](../design/heart-fixed-witness-replay.md)。本阶段仅交付父监督所需的进程组件，不是完整 supervisor、普通 JVM 入口、来源认证或诊断结果；没有执行历史22步。

## 本次新增修改

`scripts/fixed_witness_replay_process.py` 的 `run_process` 只接收显式 argv，一次启动、无 shell、无重试；Linux 独立 session/process group，stdout/stderr 使用非阻塞管道、二进制保留与独占日志。共享 `OutputBudget` 跨阶段累计两条输出流，不分别给每个进程10MiB。到达输出上限后仅保留允许的前缀，多读的字节只记录为 observed lower bound，立即终止。超时、信号中断、启动失败与非零退出分别保留，不把 EXITED 当作证据完成。

阶段 timeout 同时受调用方绝对总 deadline 限制；无论正常退出、超限、中断还是 leader 提前退出，finally 都 SIGKILL 整个所拥有的 group，再 wait/reap 直接子进程，保留已刷新的日志。不等待 TERM 宽限期，避免不响应 TERM 的后代拖延；管道仍被后代持有时继续受原 deadline 限制。清理 wait 最多额外2秒，最终装配时须在180/900秒父/总预算内预留清理与认证时间，不能把这些时间转给 JVM。RSS 未采样，明确 null，不把 heap/metaspace 当作 RSS 硬限制。

该组件不是隔离沙箱，不阻止恶意 setsid 逃逸或网络；只用于已审查的协作子进程。未来普通 JVM 入口仍须落实离线限制、固定 `-Xmx256m/-XX:MaxMetaspaceSize=128m`、真实 argv/class/source 认证和 journal 语义核验。这里的输出上限只覆盖 stdout/stderr，最终监督必须将 journal/manifest/report 等 artifact 一并纳入10MiB总预算，不能从本组件通过推断整个预算已落实。

## 人工验证与 CI 路由

Python 共9项：2项预算校验、7项 Linux 真实进程测试，覆盖二进制 partial/非零退出、timeout保留前缀、跨阶段stdout/stderr洪流上限、绝对deadline/非法时间、独占日志/启动失败、leader退出后持管道且忽略TERM的子进程，以及父进程SIGTERM清理。只启动人工短Python进程，不读取历史输入、启动Java、生成seed或执行搜索。

`FixedWitnessProcessTest` 在标准 Wrapper verify 的 Linux suite 中调用这9项并拒绝跳过结果。Windows明确跳过该JUnit test和7项Linux测试，不能算Linux进程语义验证。新增Java测试触发现有CI，无需修改workflow或selector；固定回放专属路径不触发现行population模式。不改原GoalWindow/TerminalCertificate监督、原guard、生产/构建输入或冻结协议。

## 剩余完整实现门

尚须普通JVM入口、clean Wrapper600秒、JVM120秒/父180秒/总900秒的完整装配、含artifact的10MiB监督、source/classpath/dependency/loaded-class/argv身份认证、前后漂移与shadow拒绝、实际journal的独立检查、兼容性刷新、最终整体Review/适用CI与唯一session登记。不得因本组件交付执行历史22步或重跑#140/GoalWindow。#95/#96、reserved/provider、runtime/default仍未解锁。

不改变生产系统架构或运行方式；研究索引增加导航，README/AGENTS/ADR无需同步。
