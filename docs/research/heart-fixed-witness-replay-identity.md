# Fixed-witness 字节身份组件

本组件接续 [已接受设计](../design/heart-fixed-witness-replay.md) 与
[进程组件](heart-fixed-witness-replay-process.md)，仅提供独立身份清单原语。
不启动构建、JVM 或历史回放，不读取历史 fixture，也不调用原 GoalWindow/
terminal-certificate prepare、source-state 或 frozen guard。

`scripts/fixed_witness_replay_identity.py`：

- `source_identity` 绑定 clean Git checkout 的 HEAD 与每个 tracked 文件 SHA256；
  拒绝 dirty/untracked、隐藏 index flags、src/.mvn 下 ignored 输入、链接及越界路径。
- `classpath_identity` 绑定 main/test 全部编译文件（含资源）、明确顺序的 classpath
  和每份 jar 的完整 SHA256；拒绝 main/test 与依赖之间同名 class、重复 jar 路径、
  jar 内重复 entry、隐式 manifest Class-Path 与链接。module-info 只免于同名类比较，
  字节仍由 jar/编译清单绑定。单 jar 的 multi-release 同名变体共享一个 owner；
  跨 jar 比较保守纳入全部版本，可能拒绝当前 JDK 不选用的冲突版本。
- `verify_identity` 比较由调用方独立保存的期望清单；必须在后续构建/执行前后分别比较。
  清单自身不证明可信编译器、加载来源、真实 argv 或诊断结果。

9 项小型人工 Python 用例覆盖资源/依赖漂移、顺序、shadow、manifest continuation、
重复 entry、链接、dirty/hidden/ignored 与 Linux 实际 Git inventory；
`FixedWitnessIdentityTest` 在标准 Linux Wrapper suite 强制运行全部用例，不接受 skip。
Windows 的 symlink/真实 Git 用例可跳过，不当作 Linux 证据。其余 source 拒绝反例用 mock
隔离 Git 命令响应，并非历史构建认证。

本次 Linux CI 另发现原进程测试在 `/proc/<pid>/stat` exists/read 间被 init 回收的竞态。
测试改为一次读取并接受 FileNotFoundError，增加确定性的回收/存活/zombie 反例；
进程桥接相应强制 10 项用例。未修改生产进程监督逻辑或降低存活子进程失败条件。

仍待整体实现：600 秒 clean build、普通 JVM verify-only/诊断入口与真实 loaded-class/argv
观测、JDK/Wrapper/environment 认证、独立 retained manifest digest、前后漂移检查整合、
120/180/900 秒监督及 cleanup reserve、全 artifact+pipe 合计 10MiB、实际 journal checker、
完整最终 Review/CI、静态兼容性/hash 刷新、唯一 session 登记。组件通过不授予执行历史
22 步的权限。没有新 population、solver、provider 或 runtime/default adoption。

架构/运行方式未改变；README/AGENTS/ADR 无须变更，研究索引添加导航。
