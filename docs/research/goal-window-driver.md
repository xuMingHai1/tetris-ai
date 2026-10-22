# GoalWindow session driver

关联 #94。scripts/drive_goal_window.py 串联已接受的构建预检、进程监督、
raw/session header 审计、逐局 adapter、derived bundle 和合并 prototype 门。
普通运行默认仅预检：

~~~bash
python3 scripts/drive_goal_window.py --root "$PWD" --output /tmp/new-goal-window-preflight
~~~

标准 Linux JDK27 测试从干净 checkout 执行此默认入口，核对没有 warmup/game。
仅当原设计的实现、独立 Review、适用 CI 与采集前置全部满足后，才可显式增加
--collect 运行固定完整 session。本次实现和 synthetic 测试不执行该真实采集。
没有 seed、horizon、JVM flags、缩短 warmup 或重试覆盖开关。

## 数据链路

collect 模式先预留 checkout 外的新目录及 INCOMPLETE marker，执行已有 clean-build/
verify-only 预检并独立保留本次 manifest 原始 bytes/digest。仅预检成功后，使用原监督器
在一个直接 JVM 内运行两臂；固定 2700 秒包含 warmup。prepare 的 300 秒与预检的
120 秒仍分别计算。超时、取消、非零退出不会启动后续 arm/seed 重试。

测量结束后复核同一构建的源码/JDK/class/JAR/log 与 manifest；读取 raw 一次，
由同一 byte snapshot 完成全流审计、19 个 loaded-class header 绑定、逐局转换，
并从实际 warmup 完成记录累计两臂各 100 次决策。两份 envelope 共享同一次
raw/manifest 引用、真实环境与暖机记录，使用完整冻结 GoalWindow candidate config。
环境记录来自父进程与已校验 manifest/POM，不读取一份可任意填写的 metadata 文件。

bundle 保留原始 records/metadata/protocol、开发20/校准60 envelope 和 baseline-derived.csv。
reader 验证 layout/hash 并重新派生后，以其内存 bytes 生成 prototype-report.json；
baseline-report.json 由未修改的旧 baseline analyzer 读取兼容 CSV 得出，不额外运行第三臂。
report.json 记录 owned process 结果、构建前后复核、header 绑定、全部 artifact hash 和门禁结论。

## 完整性与权限

只有最终报告成功写入且 measurement_complete=true 才移除根 INCOMPLETE。
原型门失败是 PROTOTYPE_FAILED：保留完整证据并停止，不将其伪装成不完整或改 seed 救活。
构建/进程/数据/写入错误或晚到取消是 INCOMPLETE，保留 stdout/stderr/raw/已写产物。
目录不可复用或覆盖，bundle 自己的完成条件也仍适用。

CLI 退出码为：预检成功或 PROTOTYPE_PASSED 返回0，完整测量但原型门失败返回1，
预检/测量不完整或无效路径返回2。

build_revalidated_before_and_after 与 raw_header_bound 描述可信本机父进程实际执行的
检查，不是签名、远程主机证明或 seed/棋盘语义的独立重放认证。纯 derive_session helper
和现有 audit/bundle/prototype 的不认证标记均保留；导入 synthetic 数据不能凭内部一致性
获得实际执行来源。原型通过最多支持后续证据评审，runtime_permission 始终 false。

双臂 reusable workflow/selector 的最终接线与实际采集仍须另外完成原前置。
#94 不关闭；#95/#96、reserved 样本、provider 及 runtime/default adoption 不解锁。
