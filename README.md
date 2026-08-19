# pi-java

`pi-java` 是 [earendil-works/pi](https://github.com/earendil-works/pi) 的 Java RPC Adapter。

它为指定 workspace 启动宿主 `pi --mode rpc` 进程，并提供 Prompt、事件、Session 和 Java Tool API。

## 环境

- Java 17+
- Maven 3.9+（仅构建源码时需要）
- 宿主 `PATH` 中可执行的 `node` 和 `pi`
- 已配置的 pi Provider 凭据和 `~/.pi/agent`

```bash
java -version
node --version
pi --version
```

Windows 可使用 `where java` 确认实际命中的 JDK。classfile version `61` 对应 Java 17；Java 8 只能识别到 `52`。

## 引入依赖

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

尚未发布到 Maven repository 时，先在源码目录执行：

```bash
mvn clean install
```

## 基本调用

一个 `PiClient` 对应一个 workspace、一个 pi 进程和当前 Session。

```java
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiResult;

import java.nio.file.Path;

Path workspace = Path.of("/workspace/project");

try (PiClient client = PiClient.open(workspace)) {
    PiResult result = client.prompt("Review the current changes")
            .completion()
            .toCompletableFuture()
            .join();

    System.out.println(result.text());
}
```

`PiClient.open(...)` 会同步完成 workspace 校验、pi 启动和 `get_state` readiness 检查。调用方必须关闭 Client，推荐使用 try-with-resources。

## Activity

`prompt` 返回 `PiActivity`：

```java
PiActivity activity = client.prompt("Implement the requested change");

PiResponse accepted = activity.accepted()
        .toCompletableFuture()
        .join();

PiResult result = activity.completion()
        .toCompletableFuture()
        .join();
```

- `accepted()` 在根 `prompt` RPC 被接受或拒绝时完成。
- `completion()` 在收到 `agent_settled` 后完成。
- `events()` 发布本次 Activity 的事件。
- `steer(...)`、`followUp(...)` 和 `abort()` 只操作当前 Activity。

一个 Client 同时只能有一个 active Activity。完成后 Client 回到 `IDLE`。

自定义 timeout 和图片：

```java
PiPrompt prompt = new PiPrompt(
        "Describe this screenshot",
        List.of(new PiImage(base64Data, "image/png")),
        Duration.ofMinutes(2)
);

PiResult result = client.prompt(prompt)
        .completion()
        .toCompletableFuture()
        .join();
```

`PiPrompt.text(message)` 的默认 timeout 是十分钟。`PiImage.data` 是不带 data URL 前缀的 Base64 内容。

## 事件

Client 和 Activity 都提供 `Flow.Publisher<PiEvent>`：

```java
activity.events().subscribe(subscriber);
```

`PiEvent.raw()` 保留完整上游 JSON。文本增量可直接读取：

```java
event.textDelta().ifPresent(System.out::print);
```

最终文本以 `message_end` 中的 assistant message 为准。

## Session 与通用 RPC

读取当前 Session：

```java
PiResponse state = client.request("get_state")
        .toCompletableFuture()
        .join();

String sessionId = state.data().path("sessionId").asText();
String sessionFile = state.data().path("sessionFile").asText();
```

切换或新建 Session：

```java
client.request("switch_session", Map.of("sessionPath", sessionFile))
        .toCompletableFuture()
        .join();

client.request("new_session")
        .toCompletableFuture()
        .join();
```

当前 pi `0.84.2` 的 `switch_session` 使用 `sessionPath`。跨进程恢复时应保存 `sessionFile`。

除 `prompt`、`steer`、`follow_up` 和 `abort` 外，其他 pi RPC command 可通过 `request(command, arguments)` 调用。RPC response timeout 固定为 15 秒。

## Java Tool

使用 `@PiTool` 暴露当前 JVM 中的同步方法：

```java
public final class TicketTools {
    private final TicketService service;

    public TicketTools(TicketService service) {
        this.service = service;
    }

    @PiTool(
            name = "find_ticket",
            description = "Find a ticket by its identifier"
    )
    public Ticket find(
            @PiToolParameter(
                    name = "id",
                    description = "Ticket identifier"
            ) String id
    ) {
        return service.find(id);
    }
}
```

注册 Tool 实例：

```java
try (PiClient client = PiClient.open(workspace, new TicketTools(service))) {
    PiResult result = client.prompt("Find ticket APP-42")
            .completion()
            .toCompletableFuture()
            .join();
}
```

支持的参数类型：

- primitive 和 wrapper
- String
- enum
- array 和 collection
- String-keyed map
- record 和嵌套 record

Tool 方法必须是 public、非 static、同步实例方法。`CompletionStage` 返回类型不受支持；参数不能使用普通 Bean、递归类型、不明确泛型或非 String Map key。

Tool 实例可能被并发调用。Activity abort 不会强制终止已经进入的 Java 方法；业务代码应为 HTTP、数据库等下游操作设置 timeout。

## 宿主 pi 配置

Provider、Model、凭据、Session 存储和 `defaultTools` 均由宿主 pi 管理。pi-java 继承宿主环境并使用默认 `~/.pi/agent`。

每次启动固定使用：

```text
--mode rpc
--no-approve
--no-context-files
--no-extensions
--no-skills
--no-prompt-templates
--no-themes
```

这会隔离 workspace 中的 pi 配置资源。注册 Java Tool 时，pi-java 只额外加载为当前 Client 生成的临时 Extension。

## 并发

- 一个 workspace 使用一个独立 Client。
- 一个 Client 同时运行一个 Activity。
- 多个 Client 可以并发运行。
- JDK 21+ 自动使用虚拟线程。
- JDK 17 使用共享 daemon cached thread pool。

每个 Client 都会启动一个 Node/pi 进程。容量上限通常由进程内存、文件句柄和 Provider 限流决定，应用层应控制 Client 数量。

## 异常

- `PiRequestRejectedException`：pi 拒绝 RPC command。
- `PiRequestTimeoutException`：RPC response 超时。
- `PiActivityTimeoutException`：Activity 未在 timeout 内完成。
- `PiProcessExitException`：pi 进程意外退出。
- `PiToolRegistrationException`：Tool 方法或参数类型不受支持。

Activity timeout 后 Client 状态为 `FAILED`，应关闭并重新创建。

## 示例与构建

最小命令行示例：[`PiCliExample.java`](src/test/java/io/github/idoly/pi/example/PiCliExample.java)

```bash
mvn clean verify
```

真实 pi Tool smoke test：

```bash
mvn -Dpi.test.real=true -Dtest=PiToolRealPiSmokeTest test
```

构建生成普通 JAR、sources JAR 和 javadoc JAR。编译固定使用 `--release 17`。

内部实现说明见 [pi-java-framework-design.md](pi-java-framework-design.md)。

## License

[Apache License 2.0](LICENSE)
