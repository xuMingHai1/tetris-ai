# HEART paired 分析接口 v1

交付任务：[#105](https://github.com/xuMingHai1/tetris-ai/issues/105)，供 #94 runner 与 #95 独立验证复用。冻结判据来自 [HEART v1.1](heart-acceptance-v1.md)，分析器不运行 planner、不重建棋盘规则、不接线 Actions。默认玩法及 runtime safety 没有变化。

`scripts/analyze_heart_paired.py` 消费一个 UTF-8 JSON，输出完整 JSON、逐 seed CSV 和可读门禁摘要。缺失字段、不一致配对或不合法数据返回 2；有效的 measured validation 门禁失败返回 1；synthetic 和开发/校准为诊断，返回 0，但逐门禁仍如实标记失败。只有 measured validation 的全部 headless 门禁通过，才输出 `eligible_for_runtime_validation=true`，含义仅为进入 #96 的运行验收，不能据此采用或切换默认策略。

## Envelope 与运行元数据

顶层字段均必填：

| 字段 | 值/约束 |
| --- | --- |
| `schema` | `heart-paired-v1` |
| `protocol_sha256` | 冻结 JSON 文件原始 bytes 的 SHA256；当前 `23dea1ad0d74ddfda04d443266d3cca9583d790aaceba6bc3afd98cd36896280` |
| `population` | `development`、`calibration` 或 `validation`；必须包含协议该 population 的全部且仅有 seeds，两臂一致 |
| `evidence_kind` | `synthetic` 或 `measured`；合成样本永不具备进入 runtime 验收的资格 |
| `timing_scope` | `agent.plan-through-validated-terminal-placement`，含规划到合法终结落点验证，不含预探测/observer/输出 |
| `input_boundary` | `post-spawn-gravity-known-preview` |
| `reset_strategy_per_game` | JSON boolean `true` |
| `environment` | 必须记录非空字符串 `os`、`arch`、`cpu`、`ram`、`jdk`、`javafx`、`jvm_flags`、`clock`；无额外 JVM flags 时显式写 `none` |
| `arms` | 恰好 `baseline` 和 `candidate` |

每臂包含：`source_sha`（40 位小写 Git SHA）、`config`（对象）、`config_sha256`、`warmup`、`games`。baseline 额外必填 `production_base_sha`，等于协议的 `2d3f418afdc0454d160cda3599c5c688a8b1f670`；`source_sha` 是实际测量 checkout，可包含测量实现。runner 必须保存证明基线生产树一致的 source guard 输出，不能只凭字符串证明一致。

`config` 必填 `agent`、`rows=20`、`cols=10`、`target=heart`、`piece_horizon=1000`、`preview_rescue`（boolean）。baseline agent 为 `build-shape`、rescue 为 false；candidate 要显式记录 agent 与所有策略参数/资源预算，允许额外字段。配置 hash 使用 Python `json.dumps(config, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)` 的 UTF-8 bytes；可复用分析器 `digest(config)`。两臂都必须完整写出 `warmup={"seed_start":19000,"games":10,"max_pieces":10,"decisions":100}`，warmup 不入测量结果。

同一 envelope 的两臂必须使用相同实际环境、同 seed 7-bag、同输入与 horizon；偶数 seed baseline 先运行，奇数 seed candidate 先运行。元数据是 runner 的声明；分析器验证格式/一致性，不能证明所声明的二进制、计时环境、合法动作、信息隔离或独立样本未污染。#94/#95 仍须保存 checkout/config、原始 trace、执行顺序、生产验证和预注册证据；非法计划次数不能靠声明 0 替代动作验证。当前工具不负责登记被消耗的 validation population。

## 每臂逐局数据

`games` 为对象数组，每个 seed 恰好一行。直接映射现有 CSV/record 字段，不省略未完成值：

| 字段组 | 必填字段 |
| --- | --- |
| 身份/推进 | `seed`、`run_order`（该臂 0=先、1=后）、`piece_limit`、`pieces_placed`、`decisions`、`reached_piece_limit`（boolean） |
| 正确性 | `primary_failures`、`fallback_decisions`、`illegal_plans`（非负整数，分别报告） |
| 首次与持续 clean | `first_clean_piece`、`first_clean_elapsed_ms`、`post_clean_pieces`、`clean_turns`、`max_clean_streak`、`clean_at_end`（boolean）、`elapsed_game_ms` |
| 形状汇总 | `best_visual_error_cells`、`max_required_with_zero_forbidden`、`min_forbidden_at_full_required` |
| 完整计时 | `total_decision_nanos`、`max_decision_nanos`（未舍入整数）、`decision_facts` |

`GameBenchmarkResult` 提供推进/primary failure/fallback 和完整 nanos；`ShapeCompletionTelemetry` 提供首次/持续 clean；`BuildShapeGameTelemetry` 提供形状汇总。当前 CSV game 只有舍入的 avg/max 毫秒，因此转换器应从逐 timing 行计算精确 total/max nanos，不能用舍入毫秒逆推出 nanos。`illegal_plans` 和 `run_order` 当前 CSV 不提供，#94 runner 必须显式补齐，不能补零或猜顺序。

`decision_facts` 长度等于 decisions/pieces，按 decision 1..N 连续排列；每行必填：

```json
{
  "decision": 1,
  "decision_nanos": 1000000,
  "selected_matched_required_cells": 0,
  "selected_forbidden_occupied_cells": 0,
  "selected_visual_error_cells": 32,
  "clean_completion": false
}
```

以上是合成示例，不是采集结果。形状字段来自生产 post-row-clear placement 的事实，复用现有 build_shape_decision/timing 输出。分析器以固定 32 REQUIRED / 16 FORBIDDEN 做范围与代数核对，并重算聚合、first-clean、最长 streak/end、post-clean 和计时总和，避免单独伪造 summary。它不重新实现碰撞、消行或合法性判定。

无 clean 使用 piece/time `-1`、post-clean 0；从未全覆盖的 intrusion 使用 `-1`，有全覆盖且零侵入为真实 0。无完成者的时间/块数中位数输出 null，同时保留分母；any-clean 和前 500 块完成子样本分开。第 500 块计入采用完成率，501 不计；两者都保留后续实际存活和 any-clean。每局继续至死亡/1000 块，不能在完成时提前终止。

## 判据与结果

构造 rate 与两侧 Wilson 95% 下界、单侧 exact McNemar/binomial、mean/median piece delta、回归比例、最大损失、baseline survivor loss、horizon rate loss 均独立判定。McNemar 统计 candidate-only 对 baseline-only 的前 500 块 clean；零 discordance 的 p=1，且必须 candidate-only 多于 baseline-only。存活净收益不能抵消尾部失败。

每臂独立报告/检查 pooled nearest-rank full-decision P95/max、primary failures、fallback 和非法计划；失效 baseline 不构成可信对照。完整 JSON 保留每局 max/最慢 seed、逐 seed outcome、shape、piece delta 和完成子样本；CSV 展平两臂 facts，JSON 每个 gate 有 observed/threshold/pass。判断仅适用于冻结 headless 协议，Windows 门禁仍未完成。

```bash
python3 scripts/analyze_heart_paired.py target/benchmark/heart-paired-input.json \
  --output target/benchmark/heart-paired-summary.json \
  --pairs-csv target/benchmark/heart-paired-seeds.csv
python3 -m unittest discover -s scripts -p test_analyze_heart_paired.py -v
```

测试文件的 `fixture()` 可供 #94 在未实现 runner 时生成明确标记 synthetic 的接口样本；其数字只是人工构造，不调用游戏引擎或占用保留 seeds。真实采集 JSON/CSV/摘要放 Actions artifact，不提交仓库。本任务不替代独立 Review、fresh validation 或桌面验收。
