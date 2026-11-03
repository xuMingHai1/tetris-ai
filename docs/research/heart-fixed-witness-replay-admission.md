# 固定动作回放的父进程 READY 认证组件

本组件接续 [普通 JVM 入口](heart-fixed-witness-replay-jvm.md) 与
[独立构建凭据](heart-fixed-witness-replay-prepare.md)，实现
`scripts/fixed_witness_replay_admission.py`。它没有 CLI、进程启动、stdin 写入或历史输入读取。

`authenticate_ready` 先用调用者独立保留的 receipt 复核构建，再核对 READY 的
schema、nonce、实际 PID、ordered classpath、JDK runtime version，以及 28 个生产类、
7 个测试类及编译清单中相关声明成员类的 code source、resource 路径与 SHA-256。
排除 javac 的数字 `$` 段匿名/局部/switch helper（例如 `ActionStateSearch$1`），
与 Java `getDeclaredClasses()` 口径一致；所有编译字节仍由独立 receipt 绑定。
两次真实 `/proc` 观察必须与独立构建中的 Java executable、完整命令、显式环境、cwd
和直接父进程/session/group 一致；第二次 receipt 和 READY 检查拒绝期间漂移。
JSON 重复键、未知字段、缺类/多类、错误资源、错误 nonce/PID/版本或任一来源错误均不返回 ACK。
READY 实际读取上限 1MiB，ACK 绑定原始 READY 字节而非重新序列化的 JSON。

成功只返回 `ACK nonce ready_sha256 LF` 字节及 `READY_AUTHENTICATED_NO_REPLAY` 证据。
调用者应在保持 JVM 等待状态时认证，受整体时限监督地发送 ACK 后关闭 stdin，退出后再复核
receipt/产物。返回值不写入 child-writable 目录，不自行发送 ACK，不是完整父监督成功。
当前 `run_process` 仍使用 DEVNULL；真实 JVM 管道接线、网络限制、累计 pipe+artifact 10MiB、
600/120/180/900秒预算与清理收尾、真实 journal checker 和最终整体 Review/CI 尚未完成。
本组件的哈希/来源观察不证明内存字节码或抵抗恶意进程，不认证 compiler/vendor trust。

验证仅使用人工构建清单与 READY 字节，mock 已独立测试的 prepare/proc 边界，覆盖
14 项 ACK 绑定和拒绝路径；Linux JUnit bridge 强制这些 Python 用例零跳过，
另以真实 JDK 编译产物和 `FixedWitnessJvmObservation.inspect` 比较跨语言 class 集合。
这不是实际 JVM/父管道联调、网络隔离、历史22步回放或性能实测。
现有 JDK release 的 `JAVA_VERSION` 与 READY 的 `java.runtime.version` 分开比较：
允许精确版本或同一版本的 `+build` 后缀，并通过完整 JDK byte identity 约束实际执行文件。

没有修改生产、构建、workflow、selector、原 guard、冻结协议或输入 hash。
最终整体实现仍须独立 Review/适用 CI、兼容性及 input hash 刷新、唯一 session 登记后，
才可执行一次历史回放；#140/GoalWindow 停止，#95/#96/reserved/provider/runtime 不解锁。
该组件不改变运行架构，README/AGENTS/ADR 无需同步；研究索引增加入口。
