# pi-java

`pi-java` 是 [earendil-works/pi](https://github.com/earendil-works/pi) 的 Java RPC Adapter。它从宿主 `PATH` 启动 `pi --mode rpc`，让 Java 应用提交 Prompt、接收事件、恢复 Session，并将 JVM 方法注册为 pi Tool。

## 核心模型

| 类型 | 作用 |
|---|---|
| `PiClient` | 拥有一个 workspace、一个 pi 进程和当前 Session。 |
| `PiPrompt` | 一次输入，包括文本、图片和 timeout。 |
| `PiActivity` | Prompt 提交后的运行句柄，用于等待、订阅事件、steer、follow-up 或 abort。 |
| `PiResult` | Activity 在 `agent_settled` 后产生的最终不可变结果。 |
| `PiEvent` | pi 运行过程中发送的事件，保留完整原始 JSON。 |
| `PiResponse` | 单个 RPC command 的响应。 |

它们的关系：

```text
PiClient.prompt(PiPrompt)
        |
        v
PiActivity ---------------------> PiEvent
        |
        | completion()
        v
PiResult
```

`PiActivity` 表示“正在运行的这一次任务”，包含控制和事件；`PiResult` 只表示任务结束后的结果，两者职责不同。

## 环境要求

- Java 17+
- 宿主 `PATH` 中存在 `node` 和 `pi`
- pi Provider、Model 和凭据已在宿主环境或 `~/.pi/agent` 中配置
- 从源码构建时需要 Maven 3.9+

```bash
java -version
node --version
pi --version
```

## Maven 依赖

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

尚未发布到 Maven repository 时，在源码目录执行：

```bash
mvn clean install
```

## 第一个调用

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

`PiClient.open(...)` 会同步启动 pi 并执行 readiness 检查。`PiClient` 必须关闭，推荐使用 try-with-resources。

## 使用 PiActivity

```java
PiActivity activity = client.prompt("Implement the requested change");

activity.accepted()
        .toCompletableFuture()
        .join();

activity.events().subscribe(subscriber);

PiResult result = activity.completion()
        .toCompletableFuture()
        .join();
```

运行期间可以：

```java
activity.steer("Only modify the parser");
activity.followUp("Run the tests after the change");
activity.abort();
```

一个 Client 同时只能有一个 active Activity。`completion()` 在收到 `agent_settled` 后完成。

`PiResult` 提供：

```java
result.text();      // 最终 assistant 文本
result.messages();  // 本次 Activity 的完整 message JSON
result.aborted();   // 是否以 aborted 原因结束
```

## 自定义 Prompt

`PiPrompt.text(message)` 使用十分钟 timeout。需要图片或自定义 timeout 时：

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

`PiImage.data` 是不带 data URL 前缀的 Base64 内容。

## Session

读取当前 Session：

```java
PiResponse state = client.request("get_state")
        .toCompletableFuture()
        .join();

String sessionId = state.data().path("sessionId").asText();
String sessionFile = state.data().path("sessionFile").asText();
```

恢复 Session：

```java
client.request("switch_session", Map.of(
        "sessionPath", sessionFile
)).toCompletableFuture().join();
```

新建 Session：

```java
client.request("new_session").toCompletableFuture().join();
```

当前 pi `0.84.2` 使用 `sessionPath` 切换 Session。跨进程恢复时应保存 `sessionFile`。

## Java Tool

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
            @PiToolParameter(name = "id") String id
    ) {
        return service.find(id);
    }
}
```

注册 Tool：

```java
try (PiClient client = PiClient.open(
        workspace,
        new TicketTools(ticketService)
)) {
    PiResult result = client.prompt("Find ticket APP-42")
            .completion()
            .toCompletableFuture()
            .join();
}
```

Tool 方法必须是 public、非 static、同步实例方法。参数支持 primitive/wrapper、String、enum、array、collection、String-keyed map 和 record。

Tool 实例可能被并发调用。Activity abort 不会强制终止已经进入的 Java 方法；业务代码应为 HTTP、数据库等下游调用设置 timeout。

## 运行约束

- 一个 workspace 使用一个独立 `PiClient`。
- 一个 Client 同时运行一个 Activity。
- 多个 Client 可以并发运行。
- 每个 Client 都会启动一个 Node/pi 进程。
- JDK 21+ 自动使用虚拟线程；JDK 17 使用共享 daemon worker。
- RPC response timeout 固定为 15 秒。
- Activity timeout 由 `PiPrompt` 指定。

Provider、Model、凭据、Session 存储和 `defaultTools` 均由宿主 pi 管理。

pi-java 固定关闭 workspace 中的 context files、Extensions、Skills、Prompt Templates 和 themes。注册 `@PiTool` 时，只加载当前 Client 生成的临时 Extension。

## 异常

| 异常 | 含义 |
|---|---|
| `PiRequestRejectedException` | pi 拒绝 RPC command。 |
| `PiRequestTimeoutException` | RPC response 超时。 |
| `PiActivityTimeoutException` | Activity 未在 timeout 内完成。 |
| `PiProcessExitException` | pi 进程意外退出。 |
| `PiToolRegistrationException` | Tool 方法或参数类型不受支持。 |

Activity timeout 后 Client 状态为 `FAILED`，应关闭并重新创建。

## 构建

```bash
mvn clean verify
```

真实 pi Tool smoke test：

```bash
mvn -Dpi.test.real=true -Dtest=PiToolRealPiSmokeTest test
```

最小命令行示例见 [`PiCliExample.java`](src/test/java/io/github/idoly/pi/example/PiCliExample.java)。

## License

[Apache License 2.0](LICENSE)
