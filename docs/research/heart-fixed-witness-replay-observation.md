# 固定动作回放的实际进程观测组件

接续[构建准备](heart-fixed-witness-replay-prepare.md)，补齐 Linux `/proc` 的实际启动身份读取。
这是[固定回放设计](../design/heart-fixed-witness-replay.md)的组件阶段，尚未接入整体监督或执行历史22步。

`scripts/fixed_witness_replay_observation.py` 的 `observe_process` 要求监督者显式传入从独立保留
receipt 得到的 argv、环境、工作目录和 executable SHA-256。不能用孩子报告的预期值认证孩子。
它只接受当前监督者的直接子进程，且必须是独立 session/group leader；两次读取实际 cmdline、
environ、exe/cwd 链接及 executable 文件哈希，并以 stat 的 start ticks/parent/group/session
检查退出、PID复用或观测漂移。单个 proc 文件最多读取1MiB，不相信伪文件的 size=0。
任一缺失、拒绝访问、重复环境key、NUL截断、argv/classpath顺序或字节差异都抛错，不转成通过。
stat comm 含空格或右括号时仍从最后一个右括号解析；僵尸/死亡进程拒绝。

成功记录保留实际argv与身份，只对匹配的显式环境写 sorted UTF-8 `key=value` NUL串 SHA-256；
不输出不匹配环境的真实值。`OBSERVED` 不是 JVM/authentication/session 成功：本函数不检查
命令本身是否满足冻结 JVM 参数，也不证明实际 loaded classes、网络隔离、RSS或历史回放结果。
没有持有孩子的握手时，这只是事后观测。整体监督必须先让普通 JVM 在不回放的入口等待，
完成本观测及其他来源/预算门后才能放行；进程提前退出必须保留INCOMPLETE，不自动重试。
这是合作进程核对，不能阻止认证之后同PID exec或argv变化，不是敌对进程隔离。

13项人工测试覆盖实际Linux Python子进程正向读取及错误argv，和所有上述拒绝路径。
普通Wrapper测试通过Linux JUnit桥接强制全量13项执行；Windows真实proc用例跳过，不能冒充
Linux证据。它们不运行Java诊断、历史输入、seed、solver、population或provider。

后续仍需普通JVM握手/verify-only入口、实际loaded-class来源绑定、诊断网络限制、
120/180/900秒总体监督和pipe+artifact合计10MiB、真实journal checker、兼容性刷新与唯一登记。
最终整体实现独立Review和适用CI通过以前，禁止历史22步。#140/GoalWindow保持停止，
Q/guard/冻结协议/#95/#96/reserved/provider/runtime/default均不变。

文档影响仅研究导航；生产架构和运行方式未变，无需README、AGENTS或ADR变更。
