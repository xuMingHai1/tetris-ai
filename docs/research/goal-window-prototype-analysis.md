# GoalWindow 合并80局原型判定接口

关联 [#94](https://github.com/xuMingHai1/tetris-ai/issues/94)，复用已接受的
[终局窗口设计](../design/heart-goal-window.md) 与冻结 [#105 paired 接口](heart-paired-interface.md)。
本片段只交付结果判定，不启动游戏、不转换 raw JSONL、不修改协议、候选、生产源码或工作流。
它可独立于 [#120 进程监督](https://github.com/xuMingHai1/tetris-ai/pull/120) 从主分支实施；
不是完整 driver 或采集许可，不能以 CI 排队为由放宽证据门。

## 输入与同轮约束

`scripts/analyze_goal_window_prototype.py` 依次接收 development 和 calibration 两份完整
`heart-paired-v1` envelope；先分别调用未修改的 `analyze_heart_paired.analyze`，保留所有
分组统计与正式采用级门禁。仅接受20000–20019与20020–20079，不把80局伪装成validation，
不接受重复、缺失、跨组或保留样本替换。

两份输入的环境、计时/输入边界、evidence kind、各臂source/config/hash/warmup必须相同。
两臂也须来自同一测量checkout，baseline身份及production-base由原分析器核对；candidate
的agent声明必须为`goal-window`、preview rescue=false，`config.parameters`必须包含完整的
[固定GoalWindow配置](heart-goal-window-config.json)对象（含原terminal/rank/枚举/投入边界，
不只保存其部分字段）。复用既有构建准备器的固定配置做canonical JSON核对，不另定义Q。
它不凭这些声明证明实际选择了Q或遵守候选实现。

两份envelope均须增加同一个额外字段（原#105分析器允许额外元数据）：

```json
{
  "goal_window_session": {
    "raw_sha256": "完整原始JSONL文件bytes的64位小写SHA256",
    "manifest_sha256": "本次干净构建manifest文件bytes的64位小写SHA256"
  }
}
```

示例中的说明不是有效hash。未来raw转换器须从同一完整session生成两份envelope，使其共同
引用一次每臂100决策warmup，不能拼接两个JVM或重复warmup。此工具只核对引用相同且格式有效，
**不打开/认证所引用的raw或manifest**；来源、实际class/环境、真实完成、执行顺序和原始文件
一致性仍由完整driver验证。两个相同的自报字符串不等于构建证明。

## 合并判据

严格使用全部80seed，复用原`summarize`和exact paired纯函数。每臂各自汇集全部实际声明的
decision nanos，按nearest-rank计算P95，不平均20/60组的P95，也不混合baseline与candidate：

| 门禁 | 固定条件 |
| --- | --- |
| 构造 | candidate至少1局在前500块clean（含500，不含501），candidate-only > baseline-only |
| 存活 | paired pieces差值均值/中位数均≥0；回归比例≤5%；最大损失≤100块 |
| 终局存活 | baseline survivor损失=0；horizon rate loss≤2% |
| 各臂计时 | 全80局完整决策池P95≤50ms、max≤250ms |
| 各臂正确性 | primary failures、fallback、illegal plans均=0；缺失字段报无效数据，不补0 |

分组rate/Wilson/exact-p及原采用级门全部保留为诊断，不替代这个较弱的原型门，也不能用
分组CLI的exit0推断原型通过。真实clean字段由#105检查覆盖/侵入与摘要代数一致性；分析器
不重演游戏规则，不以预测Q补充actual clean。

## 输出与退出码

```bash
python3 scripts/analyze_goal_window_prototype.py \
  target/benchmark/development-envelope.json target/benchmark/calibration-envelope.json \
  --output target/benchmark/new-prototype-report.json
python3 -m unittest discover -s scripts -p 'test_analyze_goal_window_prototype.py'
```

也可直接消费已完成的 [derived bundle](goal-window-bundle-reader.md)：

```bash
python3 scripts/analyze_goal_window_prototype.py --bundle target/benchmark/bundle \
  --output target/benchmark/new-prototype-report.json
```

`--bundle` 先复用 `read_bundle` 核对固定布局、文件 hash，并从保留的 records/metadata
重新派生两组 envelope 与 baseline CSV；随后仅用 reader 返回的同一内存 bytes 进行判定和
输入 hash 记录，不重新打开 envelope。协议也使用 bundle 内已校验的 bytes；不能同时传入
envelope 路径或 `--protocol`。原双文件入口及其默认协议保持兼容；没有选定完整输入模式则退出2。
旧格式 bundle 可以通过 reader，但若缺少共同 session 引用仍不能通过本分析器。
bundle 内部一致性不认证实际 raw/build；报告所有来源/runtime 权限仍为 false。

报告保留两个完整分组诊断、合并逐seed配对、每臂汇总及每项observed/threshold/pass，schema为
`goal-window-prototype-v1`。CLI从同一bytes快照解析并记录两份输入的SHA256，不用重新读取的
文件给已经检查的数据标记hash。输出使用新文件，拒绝覆盖旧报告；无效输入不会创建成功报告。

退出码0只表示输入有效且数值原型门全部通过；1表示有效数据的原型门失败；2表示输入/输出
无效（包括任一 envelope 或 protocol 的 JSON 嵌套超过解析器递归限制，返回2且不输出 traceback）。
即使`evidence_kind=measured`且数值通过，`provenance_verified`、
`eligible_for_runtime_validation`与`runtime_permission`仍为false。合成数据标为
`synthetic-diagnostic-only`，不能取得真实来源认证或消费新样本的权限。

本轮测试仅使用明确synthetic的20+60 envelope，没有运行warmup/planner、收集population或
使用独立/Windows seeds。原始JSONL转换、baseline兼容日志、真实文件来源认证、完整driver、
workflow/selector及最终整体实现Review仍待交付；#95/#96没有解锁。

bundle 接线回归通过真实 writer→reader→prototype CLI 验证数值通过/失败退出码、报告输入
hash、证据 bytes 不变与拒绝覆盖；另验证 INCOMPLETE、hash 篡改、重算 hash 后仍与原始输入
不一致、缺少 session 和混合模式均退出2且不创建报告。它不等于完整采集 driver 验收。

生产结构/运行方式不变，无需新增ADR或修改生产README/AGENTS；本页记录分析工具契约。
本地Python结果与JDK27 CI分开报告；#120合并后其`test_*goal_window*.py`发现模式会包含本测试，
本PR不提前假定该CI接线已存在，也不把分别通过的局部片段当作完整#94验收。
