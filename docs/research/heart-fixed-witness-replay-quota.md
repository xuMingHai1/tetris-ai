# 固定动作回放：写入前累计配额组件

本组件落实[已接受设计](../design/heart-fixed-witness-replay.md)的固定10MiB输出上限，
只提供合作式writer的共享计费原语及管道接线，不是完整诊断supervisor。
没有历史22步执行、collect入口、网络隔离或runtime许可。

## 计数与写入语义

`scripts/fixed_witness_replay_quota.py` 的 `ByteQuota` 从不超过10MiB的额度开始，
以锁保护所有分配；完整artifact在独占创建前一次性charge，管道只保留可分配前缀。
`write_exclusive` 接受精确二进制字节，超额不创建文件；创建、短写、flush/fsync失败均不返还额度。
故charge是已保留字节的保守上界，不能作为精确磁盘占用实测值。

`reserve(n)` 在父额度中永久扣除n，再交出最多n字节的子额度。子额度还可切分，
但不回收未使用余额；不能将子charge再次加到父charge。未来supervisor应先从同一根额度
切出失败记录余量，再分配普通管道和产物，失败记录没有额外的10MiB之外额度。
跨进程writer只有实际执行相同上限时才能受益；仅传一个数字或定时扫描目录不足以硬限制。

`OutputBudget(quota=shared)` 将stdout/stderr前缀写入计费给同一额度；未传quota时保持
原pipe-only行为。每个OutputBudget由一个监督线程使用，多个合作式writer可共享ByteQuota。
日志只在实际写入时记一次，不能再按相同日志的目录大小重复记账；新增保留副本则须另计。
发生I/O失败时`written`包含已扣除但可能未全部落盘的前缀，属于保守上界。

## 验证与后续门

11项小型人工Python测试覆盖并发争用、嵌套预留、失败记录余量、artifact超限不创建、
原文件保护、短写/后写失败不返还、pipe/artifact共用和耗尽后禁止启动。
Linux普通JUnit桥强制执行全部11项，含真实Python子进程超限终止；不使用历史CSV或Java诊断。
原进程/prepare/pipe测试继续验证兼容性。

本次新增修改：prepare的before/manifest/results复用管道的同一根额度，failure使用从该根
永久预留的4096字节；见[准备组件](heart-fixed-witness-replay-prepare.md)。
本次新增修改：JVM READY/verified在启动前永久预留同根额度，由实际Java writer
按精确字节写前执行，argv及认证绑定并检查合计；见[父管道组件](heart-fixed-witness-replay-pipe.md)。
明确未接线：Maven生成classpath、HOME/TMP外部writer、
实际journal/report写入尚未统一使用此额度。现有调用者仍可能自行创建新的根额度；
没有完整supervisor，不能宣称整体session上限已经落实。
下一项从这些真实writer入手，对受控写入复用root，对外部writer实施预留上限，保留失败收尾余量。
之后仍须网络限制、完整journal/supervisor、兼容性/inputhash更新、整体最终Review/CI和唯一登记，
才允许历史动作诊断。#140/GoalWindow停止、#95/#96、reserved/provider/runtime边界不变。

这是test-only离线设施，不改变生产包职责、构建输入或运行方式，README/AGENTS/ADR无需变化；
研究索引补充组件入口。不更改冻结设计预算、原guard或selector。
