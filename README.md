# pi-java

`pi-java` 是 `@earendil-works/pi-coding-agent` 的 Java RPC Adapter。它为每个 `PiClient` 启动一个宿主 `pi --mode rpc` 进程，通过严格 JSONL 完成请求、事件和生命周期管理，并允许 pi 调用当前 JVM 中的 `@PiTool` 方法。

项目以 Java 17 为编译和运行基线，CI 验证 JDK 17 与 JDK 25。JDK 21+ 自动使用虚拟线程，JDK 17 使用共享 daemon thread pool。

## 能力边界

pi-java 负责：

- 从宿主 `PATH` 启动 `pi --mode rpc`
- 严格处理 UTF-8 JSONL、RPC request ID 和并发响应
- 将一次 prompt 管理为 `PiActivity`
- 在 `agent_settled` 后完成 Activity
- 发布 Client 级和 Activity 级事件
- 管理 pi 进程、超时、异常退出和资源清理
- 将当前 JVM 的同步方法注册为 `@PiTool`
- 支持大量工作区对应的大量并发 `PiClient`

pi-java 不复制 pi 的配置系统。以下内容均由宿主 pi 决定：

- Provider、Model 和认证凭据
- `~/.pi/agent` 中的 settings
- Session 存储位置
- 内置 Tool 配置和 `defaultTools`

## 环境要求

- JDK 17 或更高版本
- Maven 3.9+
- Node.js 可从宿主 `PATH` 执行
- `pi` CLI 可从宿主 `PATH` 执行
- Provider 凭据已配置在宿主环境或 `~/.pi/agent`

建议在部署和 CI 环境中固定并检查 Java、Node 与 pi 版本：

```bash
java -version
node --version
pi --version
```

Windows 还应检查实际命中的可执行文件：

```bat
where java
```

如果报错包含 `class file version 61.0` 和 `only recognizes ... 52.0`，说明 `PATH` 命中了 Java 8。将 JDK 17+ 的 `bin` 放到 `PATH` 前面，例如：

```bat
set "JAVA_HOME=D:\plugins\jdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
java -version
```

本项目当前按 pi `0.84.2` 的 RPC 契约验证。

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

运行时库依赖仅包含 Jackson Databind `2.18.x` 及其传递依赖。

## 构建产物

```bash
mvn clean package
```

项目不发布 CLI 或 fat JAR。构建只产生库及其发布附件：

```text
pi-java-0.1.0-SNAPSHOT.jar          普通库 JAR，不内嵌 Jackson
pi-java-0.1.0-SNAPSHOT-sources.jar  源码
pi-java-0.1.0-SNAPSHOT-javadoc.jar  API 文档
```

one-shot、interactive 和 Session 操作的命令行写法仅作为集成示例保留在 [PiCliExample.java](./src/test/java/io/github/idoly/pi/example/PiCliExample.java)。示例参与测试编译，但不会进入任何发布 artifact。可通过 Maven test classpath 运行：

```bash
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=io.github.idoly.pi.example.PiCliExample \
  -Dexec.classpathScope=test \
  -Dexec.args="--help"
```

该命令仅用于查看或调试示例，不代表项目提供 CLI 发布物。

## Java API 快速开始

一个工作区对应一个独占 `PiClient` 和一个 pi 进程：

```java
Path workspace = Path.of("/workspace/project");

try (PiClient client = PiClient.open(workspace)) {
    PiActivity activity = client.prompt("Review the current changes");
    PiResult result = activity.completion().toCompletableFuture().join();

    System.out.println(result.text());
}
```

`PiClient.open(...)` 是同步启动入口。它只在以下操作全部成功后返回：

1. 校验工作区。
2. 准备 Java Tool bridge（如有）。
3. 启动 pi 进程。
4. 建立 RPC 通道。
5. 调用 `get_state` 完成 readiness 检查。

启动失败会同步清理进程、callback server 和临时 Extension。

## Activity 生命周期

`prompt(...)` 返回 `PiActivity`。它区分两个异步阶段：

```java
PiActivity activity = client.prompt("Fix the failing tests");

PiResponse accepted = activity.accepted()
        .toCompletableFuture()
        .join();

PiResult result = activity.completion()
        .toCompletableFuture()
        .join();
```

- `accepted()`：prompt RPC 已被 pi 接受。
- `completion()`：pi 已发送 `agent_settled`，Activity 完成。
- `result.text()`：最终 assistant 文本，以 `message_end` 为准。
- `result.messages()`：Activity 中完整的消息对象。
- `result.aborted()`：Activity 是否被中止。

同一个 Client 同时只允许一个 active Activity。Activity 期间可以调用：

```java
activity.steer("Focus on the concurrency bug");
activity.followUp("Run the tests after the fix");
activity.abort();
```

`steer`、`follow_up` 和 `abort` 只能通过当前 `PiActivity` 调用，不能通过 Client 的通用 `request(...)` 绕过状态管理。

## Prompt 与图片

纯文本 Prompt：

```java
PiPrompt prompt = PiPrompt.text("Describe this workspace");
```

`PiPrompt.text(...)` 默认 timeout 为 10 分钟。自定义 timeout 或图片输入时直接构造：

```java
PiPrompt prompt = new PiPrompt(
        "Inspect this screenshot",
        List.of(new PiImage(base64Data, "image/png")),
        Duration.ofMinutes(3)
);

PiActivity activity = client.prompt(prompt);
```

图片按 pi 协议编码为 `type: "image"`、`data` 和 `mimeType`。

## 事件

Client 事件流包含进程收到的全部 pi 事件：

```java
Flow.Publisher<PiEvent> events = client.events();
```

Activity 事件流只包含当前 Activity 的事件：

```java
Flow.Publisher<PiEvent> events = activity.events();
```

Activity 在首个订阅者连接前会暂存早到事件。事件统一表示为 `PiEvent(type, raw)`，未知事件不会丢失；`textDelta()` 可用于读取流式文本增量。

Publisher 使用有界缓冲区。订阅者长期无法消费时会失败，而不会无限占用内存。

## 通用 RPC

非 Activity 命令通过通用 API 调用：

```java
PiResponse state = client.request("get_state")
        .toCompletableFuture()
        .join();

PiResponse entries = client.request("get_entries", Map.of(
        "since", 100
)).toCompletableFuture().join();
```

读取当前 Session：

```java
PiResponse state = client.request("get_state")
        .toCompletableFuture()
        .join();

String sessionId = state.data().path("sessionId").asText();
Path sessionFile = Path.of(state.data().path("sessionFile").asText());
```

`sessionId` 是 Session 标识，但 pi `0.84.2` 的 `switch_session` 不接受 ID，只接受持久化 JSONL 文件路径。跨 Client 或进程重启复用 Session 时保存 `sessionFile`：

```java
client.request("switch_session", Map.of(
        "sessionPath", sessionFile.toAbsolutePath().toString()
));
```

新建 Session：

```java
client.request("new_session");
```

[PiCliExample.java](./src/test/java/io/github/idoly/pi/example/PiCliExample.java) 展示了如何用 `--session` 和 `:switch` 包装上述协议；它不是发布 CLI。

通用 RPC response timeout 固定为 15 秒。pi 进程关闭宽限固定为 3 秒。

## Java Tool

使用 `@PiTool` 将当前 JVM 的业务方法提供给 pi：

```java
public final class BuildTools {
    private final BuildService service;

    public BuildTools(BuildService service) {
        this.service = service;
    }

    @PiTool(
            name = "query_build_status",
            label = "Query build status",
            description = "Query the status of an internal build"
    )
    public BuildStatus query(
            @PiToolParameter(
                    name = "buildId",
                    description = "Build identifier"
            ) String buildId
    ) {
        return service.query(buildId);
    }
}
```

打开 Client 时传入 Tool 对象：

```java
try (PiClient client = PiClient.open(workspace, new BuildTools(buildService))) {
    // pi can call query_build_status during an Activity
}
```

pi-java 会自动：

1. 扫描公开的 `@PiTool` 方法。
2. 生成 JSON Schema。
3. 创建临时 TypeScript Extension。
4. 启动仅监听 `127.0.0.1` 的 callback server。
5. 使用每 Client 随机 Token 验证 callback。
6. 通过显式 `--extension` 加载生成的 Tool。
7. 在 Client 关闭时删除临时文件并停止 callback server。

Tool 约束：

- 方法必须为 `public`、非 `static`、同步方法。
- 不支持返回 `CompletionStage`。
- 参数名必须明确，可使用 `@PiToolParameter`。
- 支持 primitive、包装类型、String、enum、record、嵌套 record、array、collection 和 `Map<String, ?>`。
- 不支持普通 Bean、递归类型、不明确泛型、非 String Map key 和 polymorphic DTO。
- `required=false` 的顶层参数必须使用引用类型。
- 字符串返回值直接作为文本，不会增加 JSON 引号。
- Tool 对象可能被并发调用，业务实现必须线程安全。
- Activity abort 或 HTTP 断开不会强制终止已经进入的 Java 方法。
- Tool 必须为数据库、HTTP 等下游调用设置自己的 timeout 和取消策略。
- 写操作应在业务层实现授权、审计和幂等。

业务异常只向 pi 返回脱敏的 `Java Tool execution failed`，不会直接暴露 Java exception message。

## 固定资源隔离

每个 pi RPC 进程固定使用：

```text
--no-approve
--no-context-files
--no-extensions
--no-skills
--no-prompt-templates
--no-themes
```

因此项目中的以下资源不会被加载：

- `.pi/settings.json`
- packages
- context files
- Skills
- Prompt Templates
- themes
- 普通 Extensions
- 项目 system prompt

唯一显式 Extension 是 pi-java 为 `@PiTool` 生成的内部 adapter。`--no-extensions` 不阻止通过 CLI `--extension` 指定的该文件。

pi-java 不传 `--tools`。内置 Tool 由宿主 pi 的 `defaultTools` 决定；未配置时 pi 默认使用 `read,bash,edit,write`。

## 大量并发 Client

不同工作区可以并发创建独立 Client：

```java
List<PiClient> clients = workspaces.stream()
        .map(PiClient::open)
        .toList();
```

线程策略：

- 所有 Client 共享一个 Worker Executor。
- 所有 timeout 共享一个 Scheduler。
- JDK 21+ 自动使用虚拟线程，推荐用于大量 Client。
- JDK 17 回退到共享且无界的 daemon cached pool，适合并发受控的兼容运行。
- process writer、stdout、stderr、事件分发和 Tool callback 都使用共享 Worker。
- 关闭单个 Client 不会关闭共享执行器。

大量 Client 的主要限制不是 Java 线程，而是每个 Client 对应的独立 Node/pi OS 进程。部署前应压测：

- Node/pi 进程内存
- 操作系统进程和文件句柄上限
- Provider API 并发、速率限制和费用
- Session 磁盘 I/O
- 工作区存储吞吐
- Java Tool 的并发能力

必须为每个 Client 调用 `close()`。建议始终使用 try-with-resources，批量管理时则在应用 shutdown 阶段逐个关闭。

## 状态与异常

`PiClient.state()` 可能返回：

```text
STARTING
IDLE
ACTIVE
STOPPING
TERMINATED
FAILED
```

常见异常：

- `PiRequestRejectedException`：pi 拒绝 RPC request。
- `PiRequestTimeoutException`：等待 RPC response 超时。
- `PiActivityTimeoutException`：等待 `agent_settled` 超时，框架会请求 abort。
- `PiProcessExitException`：pi 进程意外退出，可读取 `exitCode()`。
- `PiToolRegistrationException`：Java Tool 在 pi 启动前校验失败。

进程退出或 transport 失败时，所有 pending request 和 active Activity 都会失败。宿主 pi 的 stderr 由 `System.Logger` 以 DEBUG 级别记录。不要自动重试状态不明确的写操作。

## 构建与验证

普通验证：

```bash
mvn clean verify
```

Maven Enforcer 拒绝低于 JDK 17 的构建。Compiler 始终使用：

```xml
<maven.compiler.release>17</maven.compiler.release>
```

生成的 classfile major version 为 61，可在 Java 17 至 25 上运行。GitHub Actions 分别使用 Temurin 17 和 25 执行 `mvn verify`，并检查 classfile、普通 JAR 依赖边界以及不存在可执行 `Main-Class`。项目采用 Apache-2.0，并随发布附带普通、sources 和 javadoc JAR。

首次正式版本发布后，可对指定旧版本执行公开 API 二进制和源码兼容检查：

```bash
mvn verify -Dpi.compat.version=0.1.0
```

检查范围为 `core`、`core.tool` 和 `protocol` 公开 API，不将 `core.internal` 视为兼容承诺。

真实 pi smoke test 默认不运行，需要显式启用：

```bash
mvn \
  -Dpi.test.real=true \
  -Dtest=PiToolRealPiSmokeTest test
```

该测试会真实启动宿主 pi、加载生成的 Java Tool Extension，并验证 callback 结果。

## 文档

- [框架设计](./pi-java-framework-design.md)
- [集成指南](./pi-java-integration.md)

## License

[Apache License 2.0](./LICENSE)
