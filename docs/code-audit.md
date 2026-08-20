# 当前代码文件审查

本文件只记录当前仓库中每个文件的职责和保留判断。

## 项目文件

| 文件 | 作用 | 判断 |
|---|---|---|
| `.github/workflows/build.yml` | Java 17、25 CI | 简单，保留 |
| `.gitignore` | 忽略构建和 IDE 文件 | 简单，保留 |
| `LICENSE` | Apache 2.0 许可 | 必要 |
| `pom.xml` | Maven 构建、依赖和发布产物 | 配置集中，保留 |
| `README.md` | 使用文档 | 必要 |
| `docs/architecture.md` | 当前内部结构 | 必要 |
| `docs/code-audit.md` | 当前文件职责审查 | 任务要求 |

## 公共 API

| 文件 | 作用 | 判断 |
|---|---|---|
| `PiClient.java` | Client 入口和公共契约 | 职责清楚 |
| `PiClientConfig.java` | 启动和 RPC 配置 | 当前选项都有直接用途 |
| `PiResourcePolicy.java` | 资源信任策略 | 独立安全概念，保留 |
| `PiActivity.java` | Activity 控制和结果入口 | 独立生命周期，保留 |
| `PiPrompt.java` | 文本、图片和 timeout | 减少方法重载，保留 |
| `PiResult.java` | 最终文本、消息和中止状态 | 简单结果对象 |
| `PiClientState.java` | Client 状态 | 对外可观察，保留 |
| `PiException.java` | 基础异常 | 统一捕获入口 |
| `PiRequestRejectedException.java` | RPC 被 pi 拒绝 | 可单独处理 |
| `PiRequestTimeoutException.java` | RPC timeout | 可单独处理 |
| `PiActivityTimeoutException.java` | Activity timeout | 可单独处理 |
| `PiProcessExitException.java` | pi 意外退出 | 保留退出码 |
| `tool/PiTool.java` | Tool 方法注解 | Tool API 必需 |
| `tool/PiToolParameter.java` | Tool 参数元数据 | Schema 必需 |
| `tool/PiToolRegistrationException.java` | Tool 注册失败 | 可单独处理 |
| `protocol/PiEvent.java` | 事件和原始 JSON | 保留协议扩展字段 |
| `protocol/PiImage.java` | 图片协议对象 | 简单数据对象 |
| `protocol/PiResponse.java` | response 和原始 JSON | 保留协议扩展字段 |

## 内部实现

| 文件 | 作用 | 判断 |
|---|---|---|
| `PiClientFactory.java` | 创建和失败清理 | 集中资源所有权，保留 |
| `PiClientImpl.java` | Client 状态协调 | 职责单一 |
| `PiActivityImpl.java` | Activity 状态机 | 独立状态较多，单独文件合理 |
| `PiConnection.java` | request/response 关联 | 协议连接边界，保留 |
| `PiTransport.java` | Transport 接口 | 生产与测试各有实现，保留 |
| `PiProcess.java` | 子进程通信 | 操作系统边界，保留 |
| `PiProcessConfig.java` | 生成进程命令和环境 | 避免进程类混入配置映射 |
| `PiCommand.java` | 出站 RPC 对象 | 避免散落 Map 拼装 |
| `PiJsonCodec.java` | JSON 编解码 | 独立协议职责 |
| `PiJsonlFramer.java` | JSONL 分帧和大小限制 | 独立传输职责 |
| `PiPublisher.java` | 有界事件发布 | 满足非阻塞和不丢事件要求 |
| `PiBufferedPublisher.java` | Activity 早期事件缓存 | 只服务明确场景，保留 |
| `PiThreads.java` | 共享 worker 和 scheduler | 多处使用，集中合理 |
| `PiToolBridge.java` | JVM Tool 回调桥 | Tool 能力必需 |
| `PiToolDefinition.java` | Tool Schema 和参数绑定 | 反射逻辑集中合理 |
| `PiToolCallException.java` | Tool 参数错误分类 | 用于区分 HTTP 400/500 |

## 测试与示例

| 文件 | 作用 | 判断 |
|---|---|---|
| `PiClientConfigTest.java` | 配置默认值和校验 | 保留 |
| `PiClientActivityTest.java` | Activity 状态和控制 | 核心测试 |
| `PiClientConcurrencyTest.java` | 多 Client 并发 | 对应公开行为 |
| `PiConnectionTest.java` | 关联、拒绝、timeout、退出 | 核心测试 |
| `PiJsonCodecTest.java` | JSON 协议行为 | 保留 |
| `PiJsonlFramerTest.java` | 分帧边界 | 保留 |
| `PiProcessConfigTest.java` | 启动参数映射 | 保留 |
| `PiProcessTest.java` | 真实子进程通信 | 保留 |
| `PiPublisherTest.java` | 慢订阅者行为 | 保留 |
| `PiThreadsTest.java` | daemon 和共享 executor | 防止 JVM 无法退出 |
| `PiToolBridgeTest.java` | Tool、鉴权和异常 | 核心测试 |
| `PiScriptedTransport.java` | 可控测试 Transport | 测试隔离必需 |
| `PiTestProcess.java` | Process 测试子进程 | 集成测试必需 |
| `PiToolRealPiSmokeTest.java` | 真实 pi 验证 | 外部集成检查 |
| `PiCliExample.java` | 最小命令行示例 | 编译期验证示例 |

当前小范围测试中的响应和事件构造保持就地定义。抽取共享测试框架会增加阅读跳转。
