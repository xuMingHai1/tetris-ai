# 固定回放普通 JVM 观测与握手组件

接续 [实际进程观测](heart-fixed-witness-replay-observation.md) 和已接受的
[固定输入设计](../design/heart-fixed-witness-replay.md)。这是 test-only 组件，
没有历史22步结果，也没有可执行回放/collect命令。

## 本次新增修改

- `FixedWitnessReplayApplication` 只接受
  `--verify-only <checkout-root> <new-external-output> <64-hex-nonce>`。
  要求 Linux JDK27、普通 classpath、实际 ProcessHandle argv 严格匹配
  `-Xmx256m -XX:MaxMetaspaceSize=128m -Duser.home=... -Djava.io.tmpdir=... -cp ...`；
  拒绝额外 JVM 参数及四种 JVM 注入环境变量。输出目录必须在 checkout 外，
  独占创建，不覆盖既有证据。
- `FixedWitnessJvmObservation` 不初始化被观察类，读取28个相关生产类型、
  七个固定回放测试类型及它们的嵌套类型的实际 classloader/code source/resource
  路径与SHA-256。拒绝非规范路径、错误origin、重复resource和超过1MiB的class。
  不调用 replay、搜索、generator、旧audit入口或JavaFX Toolkit，不读取历史fixture。
- 先强制写出 `ready.json`，保持 JVM 存活供父监督观察 /proc 并独立比对构建receipt；
  stdin只接受 `ACK <nonce> <ready文件SHA-256> LF` 然后EOF。错误/缺失/多余字节、
  未关闭管道或超时不写完成文件。ACK后再观察一次相关class，漂移则停止。
  只在验证完成时独占写出 `verified.json`，状态为 `VERIFIED_NO_REPLAY`。
  ACK等待使用daemon读线程和剩余120秒JVM预算；整个进程仍需父监督硬deadline。
- 两文件始终明确 `diagnostic_started=false`、`provenance_authenticated=false`、
  `eligible_for_runtime_validation=false`。ACK仅绑定这次观测文件，
  不是独立认证或执行授权；能向stdin写入的人可以发送ACK。

## 验证与局限

测试只覆盖人工控制消息和类文件：严格命令、错误nonce/hash/换行/额外字节/EOF、
阻塞管道超时、外部路径、无初始化的嵌套class观察、错误origin与重复resource。
Linux还以真实普通JVM检查READY等待、ACK后退出、EOF前缀保留、重复目录不覆盖、
额外JVM参数在创建输出前拒绝。Windows跳过两项Linux进程检查，不计为通过。
Surefire使用named-module patch，人工class观察用独立普通loader，
不放松入口的ordinary-classpath要求。

class resource字节不是对内存bytecode的直接读取；严格无agent参数、父进程实际
/proc观察、独立clean-build receipt和前后全部classpath/JDK/source身份须组合使用。
这里不读取或认证receipt，不采RSS，不绑定环境allowlist或实际可执行文件，
不禁止网络，也没有artifact总quota。不能把VERIFY成功称为历史诊断成功。

## 下一集成门

从最新default新分支，用独立prepare/receipt和observe_process在READY时认证
实际argv、环境、可执行文件、有序classpath及所有相关class，再发送绑定ACK。
必须增加诊断网络限制、600秒build/120秒JVM/180秒parent/900秒total
（含清理收尾）的整体监督、pipe及artifact累计10MiB，以及实际journal检查。
现有run_process使用DEVNULL stdin，尚不能完成此握手，不应直接调用本入口。
未来诊断入口须另经最终整体独立Review/适用CI、兼容性/inputhash刷新和唯一登记，
才可执行一次历史22步；本组件不能自行解锁或复活已停止候选。

没有生产/build/workflow/selector/原guard/冻结协议变化；#140/GoalWindow、
#95/#96、reserved/provider/runtime边界保持。系统架构和普通游戏运行方式不变，
README/AGENTS/ADR无需同步。
