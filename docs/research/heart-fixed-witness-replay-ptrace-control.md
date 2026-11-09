# 固定 witness：ptrace 控制传输

## 本次新增修改（2026-11-09）

已接受 #174 的 dispatcher 仍缺少控制传输。本组件扩展
`scripts/fixed_witness_replay_ptrace.py` 的 `PtraceAccess`：

- `set_options` 固定 TRACESYSGOOD、TRACEFORK/VFORK/CLONE/EXEC、EXITKILL，
  不提供弱化选项。
- `event_tid` 用原生 x86-64 的完整 64 位 GETEVENTMSG 存储，拒绝零、
  超出 Linux 正 TID 范围或高位非零；创建事件和 EXEC 的 former TID
  均需未来 dispatcher 绑定出生 ticket，返回值本身不是认证。
- `signal_info` 读取完整 128 字节 siginfo，保留带符号 header，核对
  wait signal；EINVAL/group stop 失败关闭，不推断为可重注入的 delivery stop。
- `resume_syscall` 只传递显式信号，不提供 CONT/DETACH。调用者必须先认证
  停点及完成创建预留；接口自身不授予恢复权限。错误不重试、不换操作。

9 项新增 fake-kernel 测试覆盖精确请求/位图、64 位消息、高位污染、
signal header 与不匹配、EINVAL、输入边界及失败不重试。
已有 Java Linux 桥接一次执行两个 wire 测试文件共23项；Windows 桥接跳过，
直接 Python 测试只验证模拟协议。没有调用真实 libc/ptrace 或启动受管任务。

## 尚未完成

这些方法是低层传输，不是 supervisor。TRACEME/协调器启动握手、wait loop、
每停点计费、CreationLifecycle/TaskLedger 的事件认证、线程 exec/TID 重绑定、
ABI 冻结探针例外、清理和宿主 writer 交接仍待集成。选项写入成功不证明
EXITKILL 有效，siginfo 读取成功不证明可恢复或应重注入某个信号。

用户已明确接受 #170A/#174；不再等待相同政策决定。整体最终 Review/CI、
完整 fixture/task 预留表、兼容性/inputhash 与唯一登记前，不执行实际探针、
完整 network/clean-build 或历史22步。原时间/10MiB及其他停止门不变。
本组件不改变生产架构，README/AGENTS 无需调整。
