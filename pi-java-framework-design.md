# pi-java 设计说明

本文只描述理解和维护当前实现所需的内部契约。

## 1. 结构

```text
Java application
    |
    v
PiClient
    |
    +-- PiRpcConnection
    |       |
    |       +-- PiTransport
    |               |
    |               +-- PiProcess
    |
    +-- PiActivity
    |
    +-- optional PiToolBridge
```

每个 `PiClient` 独占：

- 一个 workspace
- 一个 pi OS 进程
- 一条 stdin/stdout JSONL 连接
- 当前 Session
- 至多一个 active Activity
- 可选的 Java Tool bridge

## 2. 包

```text
io.github.idoly.pi.core
    公开 Client、Activity、Prompt、Result、状态和异常

io.github.idoly.pi.core.tool
    @PiTool、@PiToolParameter 和注册异常

io.github.idoly.pi.protocol
    PiEvent、PiResponse 和 PiImage

io.github.idoly.pi.core.internal
    Process、RPC、JSONL、publisher、threading 和 Tool bridge
```

`core.internal` 不属于公开扩展点。

## 3. 启动

入口是：

```java
PiClient.open(Path workspace, Object... toolInstances)
```

`PiClientFactory` 按以下顺序启动：

1. 验证 workspace。
2. 如果传入 Tool 实例，创建 `PiToolBridge`。
3. 由 `PiProcessConfig.create(...)` 生成命令、环境和 transport limits。
4. 创建 `PiProcess` 和 `PiRpcConnection`。
5. 启动进程及 I/O task。
6. 执行 `get_state` readiness 请求。
7. 返回 `IDLE` Client。

失败时关闭已创建的 Client、进程、callback server 和临时文件。

## 4. 进程与 JSONL

启动命令：

```text
pi
--mode rpc
--no-approve
--no-context-files
--no-extensions
--no-skills
--no-prompt-templates
--no-themes
[--extension <generated-tool-extension>]
```

Windows 使用 `cmd.exe /d /s /c pi` 解析 `pi.cmd`，其他系统直接执行 `pi`。框架继承宿主环境，并设置 `PI_SKIP_VERSION_CHECK=1`。

`PiProcess` 运行三个 I/O task：

- 单 writer：串行写 stdin，固定使用 LF。
- stdout reader：读取 UTF-8 JSONL 并发布协议消息。
- stderr reader：消费 stderr，避免子进程 pipe 阻塞。

`PiJsonlFramer` 按字节处理 frame：

- 兼容 LF 和 CRLF。
- 支持 EOF 前没有换行符的最后一行。
- 在累计过程中执行 16 MiB 上限，避免无界分配。

`PiJsonCodec` 使用 Jackson 编解码结构化 JSON。未知 event 保留为带完整 raw JSON 的 `PiEvent`。

## 5. RPC

`PiRpcConnection` 负责：

- 生成 `pj-<prefix>-<sequence>` request ID。
- 在并发 map 中保存 pending request。
- 按 request ID 和 command type 匹配 response。
- 将 unsuccessful response 转为 `PiRequestRejectedException`。
- 在 15 秒后以 `PiRequestTimeoutException` 失败 request。
- 在 transport close 或进程退出时失败所有 pending request。

`PiTransport` 只有一个生产实现 `PiProcess`。保留该接口是为了让 RPC 和 Activity 测试使用 `PiScriptedTransport`，不需要启动 OS 进程。

## 6. Activity

`PiClientImpl` 同时维护 Client 状态和当前 Activity，避免两个状态机分离。

```text
STARTING -> IDLE -> ACTIVE -> IDLE
                  -> FAILED
IDLE/ACTIVE/FAILED -> STOPPING -> TERMINATED
```

一次 Activity 的完成条件是：

```text
prompt response accepted
    +
agent_settled received
```

`agent_end` 不是完成边界。

Activity 会：

- 收集属于当前 Agent activity 的事件。
- 从 `message_end` 保存完整 message。
- 提取最终 assistant text。
- 在缺少最终文本时回退到累计 text delta。
- 在 timeout 时请求 abort，并将 Client 置为 `FAILED`。

`PiBufferedPublisher` 缓存首次订阅前到达的 Activity 事件，避免 `prompt()` 返回与订阅之间丢事件。

## 7. Publisher

`PiPublisher` 是有界的多订阅者 `Flow.Publisher`。每个订阅者拥有独立 queue 和 demand。

它不使用 `SubmissionPublisher.submit()`，因为慢订阅者可能阻塞 stdout reader；也不静默丢弃事件。单个订阅者超过容量时，只失败该订阅者。

`PiBufferedPublisher` 只增加首次订阅前的 replay，实时分发仍复用 `PiPublisher`。

## 8. Java Tool

`PiToolDefinition` 负责：

- 扫描和验证 `@PiTool` 方法。
- 生成 JSON Schema。
- 保存 Java `Type` 参数绑定。
- 使用 Jackson 转换参数和返回值。
- 调用目标方法。

`PiToolBridge` 负责：

- 启动 JDK `HttpServer` loopback callback。
- 生成每 Client 随机 Token。
- 生成临时 TypeScript Extension。
- 将 pi Tool call 转发到 `PiToolDefinition`。
- 关闭 server 并删除临时文件。

Schema 支持 primitive/wrapper、String、enum、array、collection、String-keyed map 和 record。普通 Bean 和递归参数类型会在 Client 启动阶段被拒绝。

临时 Extension 使用 TypeBox `Unsafe(schema)`，避免在 TypeScript 端再次实现 Schema 生成逻辑。

`AbortSignal` 可以取消 callback HTTP 请求，但不会强制停止已经进入的同步 Java 方法。

## 9. 线程

`PiThreads` 为所有 Client 提供共享设施：

- JDK 21+：virtual-thread-per-task executor。
- JDK 17：daemon cached thread pool。
- timeout：有界 scheduled thread pool，大小为 `max(2, min(8, availableProcessors))`。

反射调用 `Executors.newVirtualThreadPerTaskExecutor()` 是为了保持 Java 17 编译基线，同时在新 JDK 上使用虚拟线程。

共享 executor 不随单个 Client 关闭。每个 Client 的 Node/pi 进程仍然是主要资源成本。

## 10. 关闭与失败

`PiClient.close()` 是幂等的：

1. 状态切换为 `STOPPING`。
2. 失败 active Activity。
3. 关闭 RPC connection 并失败 pending request。
4. Transport 失败待写 command、取消 I/O task、关闭 stdin 并停止 pi。
5. 关闭 Tool bridge。
6. 完成 publisher。
7. 状态切换为 `TERMINATED`。

pi 进程有三秒正常退出时间，之后强制销毁。意外退出通过 `PiProcessExitException` 暴露 exit code。

stderr 通过 `System.Logger` 的 `io.github.idoly.pi.process` logger 以 DEBUG 级别输出。

## 11. 保留的内部抽象

| 类型 | 保留理由 |
|---|---|
| `PiTransport` | 让 RPC/Activity 测试不依赖 OS 进程。 |
| `PiProcessConfig` | 是 Process 的唯一配置输入，同时承载固定宿主启动规则。 |
| `PiJsonlFramer` | 在分配前实施字节上限，`readLine()` 无法替代。 |
| `PiPublisher` | 非阻塞、有界，并只失败慢订阅者。 |
| `PiBufferedPublisher` | 解决 Activity 早期事件竞态。 |
| `PiToolDefinition` | 隔离类型/Schema 逻辑与 HTTP IPC。 |
| `PiToolBridge` | 承担 Node 到 JVM 的实际调用通道。 |
| `PiThreads` | 集中 Java 17/21 线程选择和共享 scheduler。 |

## 12. 测试

普通测试覆盖：

- JSONL chunk、CRLF、EOF 和大小上限
- RPC correlation、reject、timeout 和进程退出
- Activity acceptance、settled、replay、abort 和 timeout
- 真实 Java 子进程 stdin/stdout/stderr
- 固定 pi 启动参数
- Publisher overflow
- Java Tool Schema、鉴权、调用和错误清洗
- 50 个并发 scripted Client
- JDK 17 fallback 和 JDK 21+ 虚拟线程

`PiToolRealPiSmokeTest` 默认关闭，用于验证真实 pi Extension 加载和 Java callback。当前验证的上游版本是 `@earendil-works/pi-coding-agent` `0.84.2`。
