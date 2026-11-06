# 固定 witness：父权威描述符与受控快照

状态：设计候选，待最终 SHA 独立 Review、适用 CI 与正常合并；尚无实现或诊断结果。
基线 `main@d280d13a64e9413a8cb998c0300d6825076f1173`，接续
[已接受请求设计](heart-fixed-witness-report-boundary.md)及 PR178 的独立请求组件，跟踪 #91。

## 缺口与选择

`FixedWitnessReportRequest` 已持有 input/journal 字节并输出规范声明及其 SHA-256，
但没有反序列化入口或文件读取职责。`FixedWitnessReplayApplication` 仍只有 verify-only；
父端 admission/pipe 仍不认证 report 请求。上述缺口不构成既有组件缺陷。

| 方案 | 代价与边界 | 选择 |
| --- | --- | --- |
| 保持嵌入式字节请求 | 调用者继续各自解析声明、读取文件；无新接口 | 保留退路 |
| 纯描述符解析及受控文件快照 | 单独审查外部声明到已有请求的失败边界，无子进程或新输出 | 推荐下一独立组件 |
| 完整 report-only CLI/READY/receipt | 同时改变命令、两侧加载清单、产物终态与整体监督 | 后续另行设计 |

拟新增同包 test-only `FixedWitnessReportDescriptor`：严格字节解析，以及从调用者指定的
私有目录读取固定三个文件、构建已有 Request。它不写文件、不执行动作、不启动进程。
不新建生产 API，不反射绑定非导出包，不修改既有 Request 的规范字节或状态语义。

## 权威来源及固定声明

父调用者必须在读取目录前持有独立登记的 `expectedRequestSha256`；
不能取自目录、journal START、子进程 READY、自报报告或本次解析结果。
纯组件不能证明该参数的来源，真正父监督必须保留声明原件并绑定 receipt/命令/前后身份。
本设计只验证传入权威摘要与消费字节的一致性，不认证调用者或历史来源。

描述符就是 PR178 `canonicalBytes()` 的声明，固定 schema `fixed-witness-report-request-v1`。
顶层恰为 schema/inputSha256/journalSha256/paths/expected；不另嵌 requestSha256。
paths 每项恰为 id/placements；expected 的键恰为路径 ID，值恰为
required/forbidden/rawHoles/requiredHoles/forbiddenHoles/supportHoles/outsideHoles。
沿用 PathSpec、Expected 和 FixedWitnessInput 的范围与守恒约束：1–2 路径、合计最多22步，
id 是1–20位数字字符串，不能转换为数字；hash 为小写64位十六进制。

最多16KiB描述符，必须是严格 UTF-8、无 BOM 的单一 JSON object。
使用现有 Jackson 3 严格重复键检测；拒绝任意深度重复/未知/缺失键、null、
布尔/字符串冒充整数、浮点/指数形式、int溢出、尾随token，解析深度上限8。
先验证字段精确集合及节点类型再构造类型，禁止先 asInt 截断或用默认0补值。
规范整数仅十进制非负数，无负号/前导零；无需增加手写JSON parser。

构造 Request 后取得其 canonicalBytes；要求与原描述符 byte-for-byte 相等，
包括键序、path/expected顺序、紧凑分隔符及唯一末尾 LF。故空白变化、CRLF、escape替写、
错序、-0即使可被JSON解析也拒绝，不作修复。SHA-256对这些原始规范字节计算，
必须等于独立 expectedRequestSha256；还须通过 Request 的实际 input/journal hash校验。
不重新实现第二套规范序列化；相同声明只调用现有 Request 来生成身份。

固定跨语言向量（人工字节，不是历史输入或动作执行）：input 为下列两行且各有 LF，
journal 为零字节，expected 七字段均0，单路径 id="7"/placements=1。

```text
shape_feasibility_witness,seed,step,piece,actions
shape_feasibility_witness,7,1,O,HARD_DROP
```

- inputSha256：`92292cb82f47ae608e16fb73e1d47800a022e599905f7dd41a1c20ab8a61ea3b`
- journalSha256：`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
- 依照上述键序及 Request 七字段顺序的规范声明 SHA-256：
  `80f7d79d0d8ef9d1c7ee659a48a167ba39696694e5d42335cd130f2881e31b3d`

Java与Python测试必须各自从字面量构造相同规范字节并与此常量比较，不能互相调用或
动态取另一个实现的结果作为期望。空 journal 只用于描述符快照验证，不代表完成报告。

## 文件读取、所有权与明确限制

文件入口只接受绝对规范目录及独立期望摘要，固定直接子文件
`request.json`、`input.csv`、`journal.jsonl`；描述符没有路径/URI/环境展开字段。
拒绝非规范目录、符号链接（含任一祖先）、非目录根，以及非普通/链接叶文件。
目录由父调用者预先独占，读取期间禁止任何 writer；组件不创建目录、不变更权限。
这项先决条件必须由未来父监督保证，组件不声称能防御恶意同账号并发替换或建立OS沙箱。

每文件以 NOFOLLOW_LINKS 打开；打开前后及读取后检查 BasicFileAttributes 的
非null fileKey、普通文件类型、size、lastModifiedTime，任一漂移或无法取身份则拒绝。
目录在开始/结束同样核对身份及规范路径；祖先链接再次检查。
不跟随链接，不接受目录/FIFO/socket/device，不在失败后换另一条路径重试。
上述元数据检查不是对抗恶意writer的无竞态证明；hash和不重读快照才绑定消费字节。
硬链接不被冒充为已排除：其内容也必须满足独立摘要，其他链接的writer同样违反先决条件。

分别最多16KiB/64KiB/10MiB；不要仅信任stat尺寸后readAllBytes，
有界读取至各自上限加1字节，超限立即拒绝；读取顺序 descriptor/input/journal。
保留完整已读字节于内存，校验和后续 Request 构造/写报告只使用这些字节，不能重开文件。
纯byte[]入口同样先检查上限并防御复制；返回的已有 Request继续持有自有快照。
输入文件读完后的变化不改变已返回 Request；未来退出后漂移仍须父监督另外拒绝。
不创建临时副本/日志/receipt或第二个输出根，内存读取不重复计磁盘输出。

## 失败、配额与不提升资格

非法描述符/权威摘要/类型/大小/hash/元数据漂移在返回 Request前失败，
不返回部分请求、不自动产生 INVALID报告，不覆盖/删除任何证据。
I/O失败保留IOException；内容/声明校验失败保留IllegalArgumentException，
不引入可被误读成session结果的新成功状态或能力分类。
合法请求仍沿用 Reader 对损坏/截断/END 的既有分类，不能为让请求成功而修补 journal。

后续 write 必须显式传入现有同根永久预留 JournalQuota：完整报告写前扣费、CREATE_NEW、
force、失败不退沿用原实现。描述符组件不创建quota，不能证明配额祖先；
调用方保存这三文件或任何副本的字节仍应从既有root计费，不能借读输入排除新增输出。
报告写成、摘要一致与元数据稳定均不证明 provenance/Q/session/runtime资格。

## 实现、合成预算及停止条件

本设计接受后只实施上述Java test-only组件、同包测试、一个Python规范向量测试及组件说明；
不修改生产/历史fixture/Wrapper/依赖/workflow/selector/guard，
不接通Application、两侧loaded inventory、READY/ACK、receipt或最终产物集合。
引用 Request/PathSpec/Expected 复用契约，不能调用 Replay.run、模拟器、搜索或planner。

新增最多12个测试方法；参数化子例同计，禁止借参数化扩大案例数：

1. Java字面量向量与Request字节/摘要一致；Python字面量向量独立一致（共2项）。
2. 重复键、未知/缺失字段各一例（共3项，累计5）。
3. null、浮点、int溢出各一例（共3项，累计8）。
4. 父期望hash不符及input hash不符在同一用例顺序各验证一次（累计9）。
5. 私有目录有效读取后改原文件，已有Request仍消费原快照（累计10）。
6. 链接叶文件拒绝；不支持创建链接的平台明确skip（累计11）。
7. journal超限拒绝且三原件保留（累计12）。

每fixture最多2路径/每路径1步、非历史ID；不执行CSV中的动作，不读历史22行资源。
其他语法边界必须在Review中静态核对失败路径；若12项不足以验证真实修复，
先提交有理由的设计预算修订供独立接受，不能暗增参数化或运行次数。
执行前登记精确命令与截止时间：定向Wrapper测试120秒、Python向量30秒、完整verify600秒。
失败先定位，不重跑绿色检查，不追加预算或历史样本；这些是开发检查上限，
不替代原历史/网络验收进程和整体900/600/180/120秒契约。

成功条件为严格规范声明匹配独立权威摘要、消费相同有界字节、失败不改原件且零动作执行。
设计与实现各自须最终完整diff独立Review、适用CI和正常合并；
任何必须改冻结语义/生产或依赖完整网络能力的部分停止并记录，不扩大本组件。

## 后续真实接线与文档影响

真正report-only JVM模式还须独立设计：父端权威来源/私有目录无writer证明、
命令与nonce/receipt绑定、Report/Request/Descriptor及具名嵌套类双侧loaded-byte身份、
ACK前认证和退出后漂移、独立产物允许集合、共享总额度及OS网络监督。
不能用纯解析或verify-only成功冒充这些门。
PR170/174具体政策未被本设计采用，原16进程/10MiB（无HOME/TMP/target排除）继续有效；
完整clean-build网络套件和历史回放仍等待相应明确接受、整体实现Review/CI、兼容性/inputhash及唯一登记。
#140 BOUNDED_MISS、GoalWindow停止，#95/#96、reserved/provider/runtime/default边界不变。
研究索引增加设计入口；生产职责/运行方式未变，README/AGENTS/ADR无需修改。Automation保持ACTIVE。
