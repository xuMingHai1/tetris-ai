# 俄罗斯方块

> 哔哩哔哩在线视频

[![img.png](md_data/img.png)](https://www.bilibili.com/video/BV1Yx4y1S7dK)

> 游戏截图

![img1.png](md_data/img1.png)

## 开发环境

- JDK 27
- JavaFX 27
- Maven Wrapper（Maven 3.9.16）

项目使用标准 JVM + JavaFX 运行，不再使用 GraalVM / GluonFX Native Image 打包。

## 运行

Linux / macOS：

```bash
./mvnw javafx:run
```

Windows：

```bat
mvnw.cmd javafx:run
```

## 构建与测试

Linux / macOS：

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

Windows：

```bat
mvnw.cmd --batch-mode --no-transfer-progress verify
```

## 质量检查

格式检查：

```bash
./mvnw spotless:check
```

SpotBugs：

```bash
./mvnw --batch-mode --no-transfer-progress -Pci-quality -DskipTests verify
```

PIT mutation testing：

```bash
./mvnw --batch-mode --no-transfer-progress -Pci-mutation verify
```

GitHub Actions 使用 self-hosted Linux x64 runner 执行构建、SpotBugs、Gitleaks、PIT 和依赖安全检查。

本项目是开源的，不会写入和创建额外业务数据。

## AI 自动玩

游戏运行后按 `F2` 循环切换：

```text
MANUAL -> HEURISTIC -> JEV -> MANUAL
```

HEURISTIC 使用 one-ply search，在独立状态快照上枚举旋转和水平位置，并根据消行、堆叠高度、空洞和表面起伏评分。

AI 决策不直接操作 JavaFX View；`GameWorld` 只负责把 `AiMove` 映射回现有游戏动作。方块序列由独立的 7-bag generator 提供，并支持 seed，用于可重复测试和后续 benchmark。

架构说明见 `docs/architecture/ai-engine.md`。

## TypeSafe Jev

项目提供 `JevTetrisAgent`，通过 TypeSafe System One HTTP API 调用 `jev-latest`。Jev 只在 Java 已经生成并验证的合法候选中做 `Choice`，不会直接生成坐标或绕过本地碰撞规则。

运行真实 Jev 请求前设置：

```bash
export TYPESAFE_API_KEY=...
```

API key 不应写入源码、配置文件或 Git 历史。网络调用是异步的，TypeSafe 返回 429 或 529 时客户端会使用指数退避重试。

JEV 模式在新方块生成时异步请求 TypeSafe。请求等待期间当前方块暂停下落，但 JavaFX thread 不会阻塞；成功后回到 FX thread 应用 `AiMove`。远程调用失败时明确切换为 `JEV · FALLBACK` 并使用 heuristic placement；没有配置 API key 时显示 `JEV · UNAVAILABLE` 并使用同样的本地 fallback。
