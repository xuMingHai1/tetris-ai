# 固定 witness：仅写报告的普通 JVM 与父端认证

状态：设计候选，待最终 SHA 独立 Review、适用 CI 与正常合并；本稿没有实现或执行结果。
基线 `main@8d891aac7d8fffa8604c7d406ad6d6b38af67617`，接续
[请求边界](heart-fixed-witness-report-boundary.md)、[描述符设计](heart-fixed-witness-report-descriptor.md)
及 PR180 的独立组件；跟踪 [#91](https://github.com/xuMingHai1/tetris-ai/issues/91)。

## 现有证据与选择

`FixedWitnessReportDescriptor.read` 已把父端独立摘要与三个固定文件的有界自有快照绑定，
`FixedWitnessReportRequest.write` 已消费同一快照并写入 requestIdentity。
但 `FixedWitnessReplayApplication` 只接受 verify-only；两侧 loaded inventory 尚未包含
Report/Request/Descriptor，`VerifyOnlyHandshake.finalize` 只允许 ready.json/verified.json。
此外 `run_process` 成功分支硬编码 VERIFIED_NO_REPLAY，不能直接当作报告已交付。
这些是未接线职责，不是既有组件的行为缺陷。

| 方案 | 收益与代价 | 决定 |
| --- | --- | --- |
| 保持嵌入式调用 | 不新增 JVM 契约；调用者继续独立负责进程身份和产物认证 | 保留退路 |
| 独立 report-only JVM 与专用父端握手 | 只连接已保存证据到报告，明确请求、额度和终态绑定 | 推荐下一里程碑 |
| 同时完成历史 replay/网络/整体 supervisor | 可形成完整诊断，但混入未完成实现和170/174待决政策 | 本设计排除 |

推荐新增 test-only `FixedWitnessReportApplication` 和专用 Python report 握手模块，
不把 verify-only 隐式升级，不建立通用任务/插件协议。报告路径只调用 Descriptor → Request
→ Reader/Checker/Report，不调用 Replay.run、动作模拟、planner、枚举、Toolkit 或 provider。
加载相关 class 不等于初始化或执行动作。

## 父端权威、目录与命令

调用者在启动前独立持有：已验证 clean-build receipt、规范描述符原字节及其 SHA-256、
input/journal 独立摘要、固定路径/Expected 声明、同 root 额度和绝对 total_deadline。
不能从子端 READY、报告、journal START 或读取后的摘要反推这些权威参数。
已有 prepare receipt 仅绑定构建；请求另存为父进程持有的不可变 receipt，不能放进子端可写目录
再称独立。receipt 保留规范目录、三个文件的身份/大小/mtime/摘要和精确字节声明。

父调用者提供既存的私有无 writer 快照目录，恰含 request.json/input.csv/journal.jsonl；
与 checkout、HOME、TMP 和新输出目录相互分离，目录/文件沿用 Descriptor 的规范路径、
NOFOLLOW、有界读取和身份检查。其他硬链接的 writer 也违反前置条件。
父端在启动前、释放 ACK 前及退出后重核三文件和目录身份及精确摘要；
JVM 在 ACK 后才通过 Descriptor 读取自有快照，此后不重开源文件作为报告输入。
这些检查只服务合作式无 writer 前提，不宣称抵御同账号恶意替换或实现 OS 隔离。
当前 Windows JDK27 fileKey 不可得时继续拒绝，不用 path/hash 替代身份；入口仅支持 Linux。

新入口固定参数，不接受其他开关、环境默认值或历史 fixture：

```text
--report-only <checkout-root> <new-output> <nonce> <handshake-bytes> <report-bytes> <snapshot-directory> <expected-request-sha256>
```

nonce/hash 必须小写64位hex；两个额度为显式正十进制整数，handshake≤2MiB、report≤10MiB，
且二者之和及其他产物须由父根余额实际承担，不能各分配一份完整10MiB。
精确 argv 仍使用 receipt 的 JDK、-Xmx256m/-XX:MaxMetaspaceSize=128m、受控 HOME/TMP、
有序普通 classpath，拒绝注入 JVM 选项/agent/CLASSPATH。父端以实际 /proc 观察核对全部参数；
不把 child 自报的命令或 request hash 当认证。

## READY、加载字节与 ACK

新模式使用独立 schema `fixed-witness-report-jvm-v1`。READY 恰含 schema/state/nonce/
observation/handshake_bytes_limit/report_bytes_limit/request_sha256/diagnostic_started/
provenance_authenticated/eligible_for_runtime_validation，state=READY，三项权限字段均false。
父端严格重复键/字段/类型校验，并与两个独立 receipt、实际进程、精确额度及请求摘要比较。
沿用单次 `ACK nonce ready_sha256 LF` 和随后 EOF，只有监督主线程能发送；
超时、取消、身份漂移、提前退出、管道超限或拒绝认证都不释放 ACK。

Java 观察器与 Python `_classes` 增加显式 report 模式清单，保留 verify-only 原清单/命令/schema。
report 清单包含原规则/证据链及新 Application、Report、ReportRequest、ReportDescriptor；
具名嵌套类递归观察，包括实际使用的 JournalQuota 和新入口的 writer 类型。
Java Class.forName(..., false, loader) 与 Python compiled_files 的成员选择保持相等，
检查 sole resource、code source、ordered classpath、独立构建摘要和 ACK 前后/退出后漂移。
不通过只改一侧名单、只查源码或沿用旧 verify-only 成功来认证新路径。
prepare 的完整 compiled inventory 已覆盖新增 class；不伪造它已经被 JVM 执行。

## 写入、终态与失败分类

ACK 后重核加载观察并读取 Descriptor；调用 Request.write(report.json, JournalQuota)，
不生成新 journal 或 piece。成功写完后有界读取输出 report.json，仅用于计算原始字节摘要
和提取已写出的 journalState，不重放动作或另算几何。写 reported.json 前再次核对请求目录
和加载身份；任何失败不补写成功终态、不覆盖/删除前缀、不重试。

reported.json 恰含 schema/state/ready_sha256/request_sha256/report_sha256/report_bytes/
journal_state/handshake_bytes_limit/report_bytes_limit/diagnostic_started/
provenance_authenticated/eligible_for_runtime_validation；state=REPORT_WRITTEN_NO_REPLAY，
三项权限仍false。报告本身 qCertified/provenanceAuthenticated/eligibleForRuntimeValidation 仍false。

父端只在 exit0、同截止时间内完成复核后接受报告传输：输出恰为 ready.json/report.json/
reported.json 三个普通文件；严格JSON和字段类型、原始 report hash/长度、READY hash、请求摘要、
两个额度、原描述符声明与 report.requestIdentity 完全相符，所有权限字段为false。
比较声明的解析对象，不另造规范序列化器；规范原字节及其独立hash仍由父receipt保留。
父核验不重新实现 Reader/几何，不把来源一致提升为历史真实性。

| 条件 | 分类与保留 |
| --- | --- |
| 非法描述符/hash/文件类型/身份漂移、Reader拒绝格式 | 非成功退出；保留READY/已有前缀及原始错误，不伪造INVALID报告 |
| Reader为INCOMPLETE/INVALID/EVIDENCE_MISMATCH | 可写同状态报告；传输完成不改变journal状态，也不表示历史诊断完成 |
| Reader为COMPLETE | 仍只报告保存证据；不证明来源、Q、网络隔离或整体session |
| quota/I/O/取消/超时/非零退出/报告hash或终态不符 | 父监督明确失败，保留部分文件/日志/已扣额度，无重试或成功降级 |

`run_process` 仅增加封闭的成功状态选择：默认 VERIFIED_NO_REPLAY，新增
REPORT_WRITTEN_NO_REPLAY；拒绝其他值，finalize返回state必须等于父端选择。
该选择由专用父封装固定，不能由READY/任意回调返回值决定。
原 verify-only 保持旧状态和两文件集合，新 report 状态必须与 verification.journal_state 一起呈现。
原 INCOMPLETE/INVALID/EVIDENCE_MISMATCH、结构抑制和无END前缀规则不变；
报告完成绝不称 BOUNDED_MISS、Q证书或历史回放成功。

## 同根额度与有限实现门

沿用一个父 ByteQuota/OutputBudget 和绝对总deadline；启动前永久预留 H 给READY/reported，
R 给report，余额不足则不启动。Java的Artifact writer仅消费H，Request使用JournalQuota(R)，
各自写前扣费/CREATE_NEW/force，失败或未用额度不回收。父端用独立保留的H/R核对输出合计，
不能用报告的自报额度决定预算。两份独立Java计数器只在父端确实预扣H+R时才构成同根分配。
父保存三输入文件/receipt/任何副本、日志和失败记录另从同root扣费，读取不重复计费。
既存文件不假装新输出，新增副本不因称input而排除；HOME/TMP/target仍不豁免。
本组件不声称约束所有外部writer或完整clean-build磁盘产出，170/174政策不在此采用。

实现仅改 test-only Application/模式观察与人工测试、专用 report Python 模块/测试、
已有 admission 的显式模式清单和 process 的封闭成功状态选择，以及组件说明/研究索引。
不改生产、历史fixture、依赖、构建、workflow/selector、原guard、冻结协议或网络launcher。
允许使用已经编译的人工联调，不运行 prepare clean-build+network 完整验收链。
新增测试最多16项，参数化子例同计：

1. 参数/额度/私有目录约束（1）；新旧双侧加载清单及嵌套身份（1）。
2. READY请求hash不符、额度不符、receipt漂移、实际argv不符，各1（累计6）。
3. 无ACK/EOF无报告（1）；人工真实普通JVM ACK后写三文件并核对身份/hash（1，累计8）。
4. 保存journal分别INCOMPLETE/INVALID/EVIDENCE_MISMATCH/COMPLETE各1（累计12）。
5. 同root余额不足启动前拒绝（1）；写失败预留不退与既存输出保留（1，累计14）。
6. 退出后输入或报告漂移拒绝（1）；成功状态封闭且verify-only兼容（1，累计16）。

每fixture最多2路径/每路径1步、人工非历史ID；journal手写合法格式，不调用Replay.run或
动作模拟来生成fixture，不读历史22行CSV。Linux联调使用现有编译目录和显式人工receipt，
注明mock构建receipt不等于真实clean-build来源认证；Windows平台skip不算Linux执行。
既有超时/取消/管道洪泛测试继续有效，不复制为新增案例来扩大预算。
执行前登记精确命令和绝对截止：定向Wrapper≤120秒、Python≤30秒、完整verify≤600秒。
每个JVM≤120秒、父阶段≤180秒、共享总命令≤900秒且保留已有清理预留；不重置时钟。
失败先定位；若16项不足或必须改变冻结/生产语义，先提交设计修订，不加预算或换名执行。

设计最终独立Review/适用CI/正常合并只接受上述实现范围；实现最终revision再经独立Review/CI。
成功条件是零动作、独立请求与实际消费字节/加载字节/报告一致、同根永久分配、分类不提升，
旧verify-only兼容。证据不足就保留嵌入式边界，不能为接线放宽身份或来源检查。
完整网络/历史supervisor仍需170/174具体接受、整体实现Review/CI、兼容性/inputhash刷新与唯一登记。
#140 BOUNDED_MISS、GoalWindow停止，#95/#96、reserved/provider/runtime/default门保持。
本设计只改变测试工具职责；研究索引增加入口，生产README/AGENTS/ADR无需改动。
