# 固定动作回放：独立构建准备组件

本阶段接续 [环境组件](heart-fixed-witness-replay-environment.md) 与已接受的
[固定动作回放设计](../design/heart-fixed-witness-replay.md)。仅交付构建准备函数，
不含诊断入口，不读取历史 CSV，不运行历史22步、solver、seed/population或provider。

## 行为与来源

`scripts/fixed_witness_replay_prepare.py` 独立实现 `prepare` / `verify_prepared`，
不导入原 GoalWindow/TerminalCertificate prepare、guard 或 runner。调用者提供干净且
没有 target 的独立 checkout、已解析的 Linux JDK27、空的 checkout 外输出目录、共享
`OutputBudget` 与整个命令的绝对 deadline；已有 target 直接拒绝，不删除用户数据。

从零创建 HOME/TMPDIR 的受控环境显式传入每个进程和 Git inventory，排除 ambient
Java/Maven/loader/credential/Git-index 注入。`run_process` 新增可选 environment；
既有调用保持兼容，新准备路径必须显式传递。source identity 的 Git 读取也接收相同环境。
这不构成恶意主机、系统 Git 配置、编译器或依赖发行方信任证明。

首先保存源码/完整JDK/Wrapper字节清单和受控环境；实际执行指定 JDK `java -version`
及指定仓库 Wrapper `--version`，核对 release 中的精确版本、Maven3.9.16与 Maven
打印的 Java runtime 路径。版本文本是实际子进程输出，不是仅从配置反推；仍不是 JVM
真实 argv 或加载类来源观测。Wrapper 与 JDK 在使用前后都核对字节。

随后使用 Wrapper `clean test-compile` 与 pom 中显式版本的 dependency plugin
`build-classpath`，限定 test scope、冒号分隔；构建允许
下载已有依赖，不执行测试或历史诊断。绑定 main/test 编译资源及有序 JAR、拒绝 class
shadow/隐式Class-Path，再复核源码与工具链无漂移。所有版本进程及构建共享一个600秒
绝对 deadline，并受调用者最多900秒总 deadline约束，不为每个阶段重新分配600秒。

逐进程独占保留二进制stdout/stderr和结果；失败保存 `failure.json` 与已有前缀，
不创建成功manifest。成功产生 PREPARED manifest及独立返回的SHA256 receipt。
调用者必须在输出目录之外保留receipt，后续使用前后调用 `verify_prepared`；
receipt/manifest同时被替换不属于本组件的认证能力。manifest一致性不证明实际类加载。

本次新增修改：before、各stage result、manifest与stdout/stderr共用调用者的
`budget.quota`；JSON先完成严格序列化，再按UTF-8实际字节在独占创建前扣费，
包括末尾LF，后续I/O失败不退费。开始输出前从同一根额度永久预留4096字节给
failure.json；余额不足则在启动任何进程及写入前拒绝。该预留不是10MiB之外的额外额度。
失败记录仅含INCOMPLETE、截断至128字符的error、64字符的error_type、
completed_processes、累计pipe保留/观测下界计数、last_process的state/returncode及
保留/观测下界计数、diagnostic_started=false，最坏JSON转义仍小于4096字节。
不再复制完整processes/command/inventory；逐stage结果/日志保留在原文件。
若结果本身超额，失败预留仍保存真实OUTPUT_LIMIT等最后停止状态与计数，
避免只记录二次JSON额度错误而丢失原始子进程结果。
预留未使用也不回收，根charge是保守上界；不能再把子额度charge或相同日志大小相加。

本次新增修改：移除Maven的`mdep.outputFile`直接写入。插件3.11.0在未设置
outputFile/outputProperty时把`[INFO] Dependencies classpath:`及下一行classpath写到stdout；
仅在受监督构建正常退出后，从已限额保留的stdout中接受唯一、完整、无控制字符且各项为
绝对路径的记录，保持原有顺序。缺失、重复、截断或仅出现在stderr的记录拒绝，保留失败
前缀而不创建classpath/manifest。父进程按UTF-8实际字节（含末尾LF）先扣同根quota再独占
创建`classpath.txt`；拒绝超额、保护原文件，I/O失败不退费。日志与提取文件各自占用磁盘，
因此各计一次；不把读取日志再次计费。这仅关闭classpath文件的直接写入缺口。

## 人工验证与后续门

`test_fixed_witness_replay_prepare.py` 二十一项人工用例覆盖实际子进程环境传递、ambient
Git/Java注入排除、版本与runtime不匹配、插件参数、已有target保护、非零构建失败前缀、
构建中源码/JDK漂移、编译class漂移、manifest替换和共享deadline。人工JDK/Wrapper
只是Python脚本，class/JAR为不加载的字节，既不构建游戏也不执行Java或历史输入。
新增额度测试验证精确JSON/管道共享、无效JSON不扣费、超额不创建、成功证据只计一次，
以及真实人工子进程淹没输出后仍保留有界失败记录而无manifest。
新增classpath用例验证UTF-8/顺序、管道与提取文件精确计费、超额不创建、原文件保护，
以及真实人工进程的缺失/重复/stdout与stderr隔离反例。Linux `FixedWitnessPrepareTest`
桥接要求二十一项全执行且无skip；Windows仅执行九项格式/receipt/额度用例，其余十二项
不作为Linux监督成功。另有Linux真实clean checkout/Wrapper构建测试，实际解析插件stdout，
核对源码/工具链/classpath身份、提取文件字节与总计费；不启动诊断JVM或读取历史输入。
远端真实结果见本阶段PR。

本组件尚未由完整诊断supervisor调用。仍缺普通JVM verify-only/诊断入口、真实argv与
loaded-class来源、诊断禁止网络机制、120/180/900秒整体监督及cleanup/finalize reserve、
artifact和pipe合计10MiB上限、实际journal checker接线、静态兼容性/inputhash刷新、
整体最终独立Review/适用CI和唯一session登记。prepare父JSON与pipe已共享额度，
HOME/TMP中的构建产物及其他外部writer尚未受此接线约束；
这些输出的适用范围和强制额度仍须在整体supervisor完成，不得称整体输出预算已实现。

不得直接调用本函数运行历史22步。#140/GoalWindow停止，生产/default/Q/原guard、
冻结协议/#95/#96/reserved/provider边界保持；无新的运行时权限。本阶段不改变生产
架构、依赖或玩法，研究导航已同步，无需更新README/AGENTS/ADR。
