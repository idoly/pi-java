# pi-java

`pi-java` 是 [pi](https://github.com/earendil-works/pi) 的 Java RPC 客户端。它启动 `pi --mode rpc` 子进程，并提供 Prompt、Activity、事件、原始 RPC 和 Java Tool 接口。

## 环境要求

- Java 17+
- `node` 和 `pi` 已加入 `PATH`
- pi 的 Provider、Model 和凭据已配置

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 完整示例

```java
import io.github.idoly.pi.core.PiActivity;
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiClientConfig;
import io.github.idoly.pi.core.PiPrompt;
import io.github.idoly.pi.core.PiResourcePolicy;
import io.github.idoly.pi.core.PiResult;
import io.github.idoly.pi.core.tool.PiTool;
import io.github.idoly.pi.core.tool.PiToolParameter;
import io.github.idoly.pi.protocol.PiResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class PiJavaExample {
    private PiJavaExample() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: PiJavaExample <workspace>");
        }

        Path workspace = Path.of(args[0]);

        // 配置 pi 子进程。GLOBAL_ONLY 使用宿主全局资源，但不批准项目资源。
        PiClientConfig config = PiClientConfig.builder(workspace)
                .resourcePolicy(PiResourcePolicy.GLOBAL_ONLY)
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // Client 关闭时会同时关闭 RPC 连接、pi 进程和 Tool bridge。
        try (PiClient client = PiClient.open(config, new EchoTools())) {
            PiPrompt prompt = new PiPrompt(
                    "Call java_echo with hello",
                    List.of(),
                    Duration.ofMinutes(2)
            );

            PiActivity activity = client.prompt(prompt);

            // accepted 只表示 Prompt RPC 已被 pi 接受。
            activity.accepted().toCompletableFuture().join();

            // followUp 会把下一条输入加入当前 Activity。
            activity.followUp("Then summarize the result")
                    .toCompletableFuture()
                    .join();

            // completion 在收到 agent_settled 后完成。
            PiResult result = activity.completion()
                    .toCompletableFuture()
                    .join();
            System.out.println(result.text());

            // Activity 之外的命令通过原始 RPC 调用。
            PiResponse state = client.request("get_state")
                    .toCompletableFuture()
                    .join();
            System.out.println("Session: " + state.data().path("sessionId").asText());
        }
    }

    // public、非 static 的同步实例方法可以注册为 pi Tool。
    public static final class EchoTools {
        @PiTool(name = "java_echo", description = "Return the supplied text")
        public String echo(
                @PiToolParameter(name = "text", description = "Text to return")
                String text
        ) {
            return text;
        }
    }
}
```

## API 说明

- 每个 `PiClient` 管理一个 pi 进程，同时只运行一个 active `PiActivity`。
- `PiClient.open(...)` 返回前会执行 `get_state` 就绪检查。
- `PiPrompt.text(message)` 使用 10 分钟 Activity timeout。
- `PiPrompt`、`steer` 和 `followUp` 都支持 `PiImage`。
- `activity.events()` 发布当前 Activity 的事件，`client.events()` 发布进程级事件。
- `prompt`、`steer`、`follow_up` 和 `abort` 由 `PiActivity` 管理；其他命令使用 `request(...)`。
- `PiResponse` 和 `PiEvent` 保留完整原始 JSON。

`PiClientConfig` 支持自定义 pi 命令、Provider、Model、RPC response timeout、环境变量和资源策略。

| 资源策略 | 行为 |
|---|---|
| `ISOLATED` | 默认；关闭 Context、Extension、Skill、Prompt Template 和 theme |
| `GLOBAL_ONLY` | 使用全局资源，不批准项目资源 |
| `TRUST_PROJECT` | 批准并加载项目资源 |

Tool 参数支持基本类型及包装类型、String、enum、数组、集合、String key 的 Map 和 record。Tool 实例可能被并发调用，业务代码需要自行设置下游 timeout。

## 构建

```bash
mvn clean verify
```

真实 pi Tool 冒烟测试：

```bash
mvn -Dpi.test.real=true -Dtest=PiToolRealPiSmokeTest test
```

内部结构见 [架构说明](docs/architecture.md)。

[Apache License 2.0](LICENSE)
