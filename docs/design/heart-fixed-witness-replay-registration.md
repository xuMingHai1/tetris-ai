# 固定动作回放：离线登记 manifest 与一次性占位设计

状态：设计候选；最终 SHA 独立 Review、适用 CI 和正常合并后才接受实施。
跟踪 #91；接续[原回放设计](heart-fixed-witness-replay.md)与
[单 JVM 接线](heart-fixed-witness-replay-execution.md)。本稿没有实现或诊断结果。

## 缺口与推荐路径

基线 main@2704faabbe60ec2d504f740fc76b4b0a936f28cf；PR184–188已交付原接线设计的16项人工案例。
`prepare` 返回独立 manifest SHA256 receipt，`verify_prepared`复核源码/JDK/classpath；
`run_execution`认证请求和真实进程后发送ACK，并核对退出证据。这些组件都不负责跨进程
唯一登记。原设计要求登记实现SHA、输入hash、兼容性、预算、精确命令、执行位置和session ID，
且失败不重试。现有 source-comparison/input-manifest 已由PR156交付，不重新转录或执行历史输入。

| 方案 | 影响 | 决定 |
| --- | --- | --- |
| 仅以聊天或普通可覆盖JSON登记 | 无并发仲裁、无法阻止新session ID绕开旧失败 | 不选 |
| 现在连接完整prepare/network/历史supervisor | 同时需要170/174具体接受、173能力和整体门 | 延后 |
| 独立有界manifest与永久独占占位API，只用人工metadata验证 | 先固定权威字节和失败语义，后续supervisor仍须单独接线 | 推荐 |

## 实施范围与不授予的权限

只新增`scripts/fixed_witness_replay_registration.py`和人工测试，接入现有
`FixedWitnessPipeTest`的Python桥及研究导航。不增加CLI、workflow、默认历史路径、
构建/启动/ACK/动作调用、生产或冻结源码修改，不读historical-witness.csv。
不导入prepare、identity inventory或execution的可执行流程；只复用quota写入原语及严格校验惯例。
本组件是预登记意图，不是执行许可；成功状态名为`INTENT_RETAINED`，不得使用READY/ADMITTED。
`diagnostic_authorized`、`provenance_authenticated`、`network_isolation_verified`、
`eligible_for_runtime_validation`与`actions_started`始终为false，拒绝输入true。
不能把metadata中的Review/CI/兼容性描述当作这些事实已被认证。

本次实施与人工验证不得创建真实历史诊断的占位，不触发其唯一次数。
未来实际登记调用仍须整体设计/实现最终Review与CI、170/174具体接受、173能力、
兼容性/inputhash刷新及明确唯一执行登记条件；本组件合并不解除其中任何一项。

## manifest权威字节

父调用者提供不可变声明；schema固定`fixed-witness-registration-intent-v1`。
字段精确为schema、study_key、session_id、implementation_sha、input_sha256、
compatibility_sha256、command、execution_location、limits、上述五个false字段。
implementation_sha为40位小写Git SHA1；另两个摘要为64位小写SHA256。
study_key与session_id各为1–64个小写ASCII字母/数字/短横线，首尾必须字母或数字。
study_key标识逻辑诊断，不能由实现SHA、时间、输入或session ID生成；同一诊断升级revision
仍必须使用同一个key，防止换名重跑。组件无法认证调用者有没有另建registry，部署责任见下节。
compatibility_sha256只绑定父端独立审核的兼容性记录原字节，不在这里重算游戏兼容性。
input_sha256也只绑定父端声明，不读取fixture；未来supervisor须对实际输入再核对。

command为1–64个非空字符串的有序argv数组；每项UTF-8不超过1024字节，禁止NUL/CR/LF，
不得拼接为shell文本。execution_location为非空绝对路径字符串，UTF-8不超过4096字节，
同样禁止NUL/CR/LF；只是声明，不为该路径创建或启动进程。
limits精确冻结为build_seconds=600、jvm_seconds=120、parent_seconds=180、
total_seconds=900、heap_mib=256、metaspace_mib=128、output_bytes=10485760、
max_processes=16、max_placements=22、max_searches=0、max_runs=1。
只接受精确JSON整数，拒绝bool、float、额外字段、重复键、null、非法UTF-8和非有限数值。
这些数值沿用当前协议；不采用174候选512政策，不排除HOME/TMP/target，也不声称已强制限制。

Python为这个新schema的唯一序列化器：UTF-8，sort_keys=true，紧凑分隔符，
ensure_ascii=true，allow_nan=false，末尾恰好一个LF；最多16KiB。
encode先校验整个对象再生成bytes；decode严格解析并重编码逐字节相等。
与Java Request的独立schema无互换关系，不复制或修改其canonicalizer。
返回独立receipt包含canonical manifest bytes与SHA256；调用者持有自有bytes副本，
不从可变文件重新取得预期摘要。人工测试不使用历史input摘要或历史session名。

## 永久占位和失败语义

`retain_intent(registry, checkout, manifest_bytes, expected_sha256, quota)`先有界校验原字节/摘要，
registry必须是已存在、调用者独占管理、无链接祖先的canonical绝对目录，位于checkout之外。
调用者负责排除其他writer（包括hardlink），同一逻辑诊断的所有调用者使用同一持久registry。
这只提供该registry内的合作式互斥，不是恶意主机防篡改、跨机器全局唯一或OS sandbox。
组件不扫描Git、不枚举registry、不创建registry、不修复其内容；checkout边界由显式参数
提供并验证，调用者不得谎报。Linux本地普通文件系统为支持平台；其他平台不宣称持久性验收。

文件名只由合法study_key生成`<study_key>.intent.json`，绝不包含session_id或实现SHA。
先从同一OutputBudget根quota按完整UTF-8字节永久扣费，再以O_CREAT|O_EXCL|O_NOFOLLOW
创建0600普通文件；写满并fsync文件、关闭，再fsync父目录后才返回INTENT_RETAINED receipt。
并发败者遇到已存在直接拒绝，不读取并认领旧record，不因内容相同返回幂等成功。
不以临时文件rename覆盖目标，不回收已扣quota；I/O失败、短写、fsync失败均抛错，
留下空文件/部分前缀，后续调用同样拒绝。不删除、不truncate、不自动换key/session/目录重试。
崩溃或文件系统不提供所需持久性时保留不确定状态，不宣称已安全登记或允许重新执行。

成功receipt另含固定registry/key与dev/inode身份；manifest bytes仍为防御性自有副本。
`verify_intent`只接受父端原receipt及同一个registry/key，使用NOFOLLOW有界普通文件读取、
前后dev/inode/size/mtime身份核对并比对完整bytes与独立SHA256；任何漂移拒绝。
它不提升状态、不修改文件、不判断诊断是否已运行。未来supervisor须保留此次receipt，
在执行前后验证并保存自己的独立开始/终态证据；不能用本record取代实际journal或报告。
生成的manifest、复制件、失败前缀各按原10MiB口径计费，没有登记组件额外额度。

## 有限人工验证与停止条件

本组件另有独立人工预算，不能重置或追加PR183的16项执行联调。
最多8个测试方法，不用参数化子案例扩张；每项可有为单一语义必要的断言：

1. 一个小型人工manifest规范编码、receipt副本及decode一致。
2. 严格格式拒绝：固定一份重复字段JSON，不产生文件或扣费。
3. 精确原协议limits：max_processes=17拒绝，不产生文件或扣费。
4. 精确额度不足在创建前拒绝，原quota余额与无文件状态保持。
5. 成功写入与两次只读verify，精确一次根计费、文件原字节一致。
6. 两个受控线程竞争同一study_key而session ID不同，仅一个成功；败者不覆盖。
7. 人工注入首次写入后I/O失败，前缀保留，同key再次尝试拒绝。
8. 写入成功后人为替换内容，独立receipt核验拒绝且不改文件。

只使用临时独占目录、人工40/64位摘要及synthetic-前缀study_key；不启动子进程，
不运行Git/JDK/Wrapper构建准备或真实历史登记。链接/目录fsync能力由静态Review检查，
平台不支持时明确skip该Linux持久写入部分，不能退化为覆盖写或声称Windows能力通过。
并发等待使用绝对deadline，线程必须结束；不能为一例重试直到通过。
执行前登记精确命令与绝对截止：Python≤30秒、定向Wrapper≤120秒、完整verify≤600秒；
单次人工验证输出≤1MiB。失败先定位，保留日志，不扩8项/次数/政策来挽救结果。

成功条件为规范字节与唯一占位/失败前缀语义通过、最终实现SHA独立Review和适用CI通过。
这不是clean-build、网络、历史动作或Windows桌面验收。实现阶段若发现需要更改原预算、
存储口径或整体验证权限，停止相关部分并记录具体依赖；不据此阻塞独立静态工作。
#140 BOUNDED_MISS、GoalWindow停止、#95/#96、reserved/provider/runtime/default门不变。
不改变生产职责、依赖或运行方式；README/AGENTS/ADR无需调整，研究导航需增加链接。
