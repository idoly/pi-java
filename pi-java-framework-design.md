# pi-java 框架设计

## 1. 定位

`pi-java` 是以 Java 17 为编译与运行基线的 pi RPC SDK，CI 验证 JDK 17 和 25。每个 `PiClient` 独占一个工作区、一个 `pi --mode rpc` 子进程和一份可变 Session 状态。

框架只负责：

- 提供宿主 pi 的 Java RPC API
- 使用 `ProcessBuilder` 启动和关闭 pi
- 处理 stdin/stdout JSONL RPC
- 将请求、响应、事件和一次 Agent activity 组织成 Java API
- 生成安全、确定的启动参数
- 将 `@PiTool` 方法桥接到 pi Extension

框架不实现 Agent loop、模型 Provider、Node.js 运行时、进程池、远程 Gateway、Spring 集成或 Web UI。

## 2. 发布边界

Maven 坐标：

```text
io.github.idoly:pi-java:0.1.0-SNAPSHOT
```

项目只发布一个普通库 JAR，内部按 package 分层：

```text
io.github.idoly.pi.core            Client、Activity、Prompt、Result 和异常
io.github.idoly.pi.core.tool       @PiTool 公开注解与注册异常
io.github.idoly.pi.protocol        Event、Image 和 Response
io.github.idoly.pi.core.internal   Process、RPC、JSONL、publisher、threading 和 Tool bridge
```

运行时仅依赖 Jackson Databind。核心不依赖 Spring、Reactor、Netty 或 Gson。普通 JAR 保持标准依赖边界，不内嵌依赖且没有 `Main-Class`。发布同时附带 sources、javadoc 和 Apache-2.0 许可证。`PiCliExample` 只存在于 test source，参与编译和测试但不进入发布 artifact。

## 3. 总体架构

```text
Java Application
    |
    v
PiClient / PiActivity
    |
    +-- RPC connection (internal)
    +-- pi process + JSONL (internal)
    +-- Activity state (internal)
    +-- shared worker + scheduler (internal)
    +-- generated @PiTool Extension (internal)
```

一个 Client 不得被多个工作区共享。需要并发工作区时，由应用分别创建 Client。命令行、服务端或编排层都由应用基于同一公开 API 实现；仓库中的 `PiCliExample` 仅演示这种集成。

## 4. RPC 与 Transport

### 4.1 Framing

stdin 和 stdout 使用 UTF-8 JSONL：

- 写入固定使用 LF
- 读取兼容 LF 和 CRLF
- 每行只包含一个 JSON 对象
- 默认单行上限 16 MiB
- EOF 时处理最后一个未带 LF 的完整行
- stdout 只解析协议，stderr 独立消费并写入 DEBUG 级别 `System.Logger`

### 4.2 并发

- 每个进程使用一个 writer task 串行写 stdin
- stdout、stderr 使用独立 task 读取，避免子进程阻塞
- request ID 关联并发响应
- command queue 和 subscriber queue 有界
- 进程退出会失败所有未完成请求
- 所有 Client、Connection、Process 和 Tool callback 共享一个 worker executor
- 所有 RPC 和 Activity timeout 共享一个 scheduler
- JDK 21+ 的 worker 自动使用 virtual-thread-per-task executor，适合大量 Client
- JDK 17 回退到共享且无界的 daemon cached thread pool，只用于并发受控的兼容运行
- 关闭单个 Client 不关闭共享 worker 或 scheduler
- 并发 Client 的主要上限是 Node/pi 进程内存、操作系统进程数和文件句柄

### 4.3 前向兼容

所有事件统一表示为 `PiEvent(type, raw)`；`textDelta()` 仅提供流式文本便利访问。新增事件无需更新枚举或 subtype，原始 `JsonNode` 始终保留。

## 5. Client 与 Activity

入口同步完成进程启动和 `get_state` readiness 检查：

```java
PiClient client = PiClient.open(workspace);
```

`PiClient` 只提供 `prompt`、通用 `request`、状态和全局事件流。`steer`、`followUp`、`abort` 属于当前 `PiActivity`；其他 RPC 命令通过 `request(command, arguments)` 调用，避免为上游每个命令维护 Java DTO。

一次 `prompt` 返回 `PiActivity`。Activity 分离两个阶段：

- `accepted()`：pi 接受 RPC 命令
- `completion()`：收到 `agent_settled`，本次 activity 完成

早于订阅发生的 Activity 事件会在首个订阅者连接时回放。最终文本以 `message_end` 为准，流式 delta 只用于增量观察。

同一 Client 只允许一个 active Activity。通用 `request` 拒绝 activity command，防止绕过 Activity 状态机。

`get_state` 返回 `sessionId` 和可选 `sessionFile`。`sessionId` 用于标识，pi `0.84.2` 的 `switch_session` 只接受文件路径；跨 Client 或 JVM 重启复用 Session 时保存并传递 `sessionFile`。`PiCliExample` 展示了 `--session` 和 `:switch` 的一种封装。

## 6. 启动与资源

内部 `PiProcessSpec` 对 workspace 执行 `toRealPath()`，并使用参数列表构建 argv。Windows 通过系统 `cmd.exe` 调用 npm 的 `pi.cmd` shim。

启动参数固定包含：

```text
--no-approve
--no-context-files
--no-extensions
--no-skills
--no-prompt-templates
--no-themes
```

框架继承宿主环境，直接使用 `PATH` 中的 `node` 和 `pi`。框架设置 `PI_SKIP_VERSION_CHECK=1`，不传 `--provider`、`--model`、`--session-dir` 或 `PI_CODING_AGENT_DIR`，由 pi 使用宿主环境和默认 `~/.pi/agent` 中的认证、settings 与 Session 存储规则。不提供 `--approve`、项目资源、显式文件资源、classpath 资源或资源注解。这样 `.pi/settings.json`、项目 packages、项目 system prompts、context files、Skills 和项目 Extension 都不能改变 Java RPC 配置。唯一显式加载的 Extension 是框架内部生成的 `@PiTool` adapter；pi 源码确认 `--no-extensions` 不影响 CLI `--extension` 路径。

## 7. Java Tool

### 7.1 API

```java
@PiTool(name = "query_build", description = "Query a build")
public Build query(
        @PiToolParameter(name = "id") String id
) {
    return service.query(id);
}
```

通过 `PiClient.open(workspace, instance)` 注册。示例 CLI 不注册默认 Java Tool，避免改变宿主 pi 的工具集；业务 Tool 只由嵌入 Java API 的应用显式传入。

### 7.2 桥接

框架在 pi 启动前：

1. 扫描公开、非 static 的 `@PiTool` 方法。
2. 检查重复名称和不支持的签名。
3. 生成 JSON Schema。
4. 在 `127.0.0.1` 随机端口启动 callback server。
5. 为每个 Client 生成独立 256-bit Token。
6. 生成调用 `pi.registerTool()` 的临时 TypeScript Extension。
7. 通过 `--extension` 加载；pi 自动启用该 Extension 注册的 Tool。

Client 关闭时停止 callback server、撤销 Token 并删除临时文件。使用者无需部署 Gateway。

### 7.3 类型边界

支持：

- String、char、boolean、整数和浮点数
- enum
- 数组和 Collection
- `Map<String, V>`
- record
- 嵌套 record

暂不支持：

- `CompletionStage` Tool 返回值
- Optional
- wildcard 和 type variable
- 普通 Bean 和参数化 DTO
- 多态或递归对象
- Bean Validation 和 Jackson property 注解
- Tool 取消及流式进度

顶层 `required=false` 参数必须使用引用类型。Tool 对象可能被并发调用，线程安全由业务代码保证。

### 7.4 安全

- callback 只监听 loopback
- callback 请求最大 1 MiB
- Token 使用常量时间比较
- 未知参数和缺失必填参数返回 400
- 未授权请求返回 401
- Java 业务异常返回脱敏的 500
- stack trace 不进入 Tool result

loopback 和 Token 只完成进程间身份确认。业务方法仍需执行用户授权、参数校验和写操作幂等。

## 8. 生命周期

`PiClient.close()`：

1. 失败 active Activity。
2. 关闭 RPC transport。
3. 终止 pi 进程，超时后强制结束。
4. 取消 writer、stdout 和 stderr task。
5. 关闭 Tool callback 并删除临时 Extension。
6. 完成 Client 和 Activity publisher。
7. 将状态置为 TERMINATED。

共享 worker executor 和 timeout scheduler 属于进程级设施，不随单个 Client 关闭。JDK 17 回退线程均为 daemon；JDK 21+ worker 为虚拟线程。宿主应用负责在自身 shutdown 流程中关闭 Client；`PiCliExample` 展示了 shutdown hook 的一种实现。

启动失败也执行相同资源回收。进程异常退出会拒绝 pending request、失败 active Activity，并保留明确异常类型。

## 9. 测试与兼容

测试分层：

- codec 和 JSONL framing 单元测试
- scripted transport Client/Activity 测试
- 真实 Java 子进程 transport 测试
- Tool Schema、认证、调用和清理测试
- JDK 17 fallback 与 JDK 21+ 虚拟线程选择测试
- 50 个并发 scripted Client 生命周期测试
- 示例 CLI Session 畸形 response 校验测试
- 可选的真实 pi ProcessBuilder smoke test
- 首次发布后由 japicmp 对指定旧版本执行公开 API 兼容检查

真实 pi 测试通过系统属性显式开启，普通构建不依赖本机 Node/pi 安装。Maven Compiler 固定 `--release 17`，Enforcer 拒绝 JDK 17 以下构建，GitHub Actions 使用 Temurin 17 和 25 执行完整验证。

当前实现以 pi `0.84.2` 运行验证，并与 `https://pi.dev/docs/latest/rpc` 及主分支 `rpc-types.ts`、`rpc-mode.ts`、`jsonl.ts` 对照。升级 pi 时先运行 RPC、Extension 加载和 Tool bridge 契约测试，再更新兼容版本说明。
