# pi-java

[Pi](https://pi.dev) 的 Java RPC SDK，支持发送输入、订阅事件、取消运行和读取结果。模型、工具和会话管理由 Pi 执行。

要求 Java 17+、PATH 中可用的 Pi，以及模型凭据。已验证 Pi 1.1.0。当前 `0.1.0-SNAPSHOT` 尚未发布到 Maven Central，可先在本地安装：

```bash
mvn clean install
```

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 使用

```java
import io.github.idoly.pi.core.PiClient;
import io.github.idoly.pi.core.PiConfig;

public class App {
    public static void main(String[] args) {
        var config = PiConfig.builder()
                .provider("openai").model("your-model-id")
                .noSession(true)
                .requestTimeout(30)
                .build();

        try (PiClient client = PiClient.open(config)) {
            var result = client.prompt("Explain this project")
                    .completion().toCompletableFuture().join();
            if (!result.successful()) {
                throw new IllegalStateException("Pi ended with " + result.stopReason());
            }
            System.out.println(result.text());
        }
    }
}
```

替换 provider 和 model 后运行。`builder()` 使用当前目录，`builder("/path/to/project")` 指定已有目录；`noSession(true)` 使用内存会话。默认配置可直接用 `PiClient.open()`。

一个 Client 管理一个 Pi 进程和会话，同时只允许一个 Activity。`accepted()` 表示输入被接受，`completion()` 等待输入被 handled 或运行已 settled。默认只保留最终文本，`retainMessages(true)` 保留完整消息。

所有公开期限均为秒：RPC 默认 15 秒、Activity 默认 600 秒。默认期限可用 `requestTimeout(30)`、`activityTimeout(600)` 配置，也可在单次 `prompt(text, 600)`、`request(command, arguments, 30)` 中指定。

事件使用 `client.events()` / `activity.events()`；Activity 提供 `steer`、`followUp` 和 `abort`。普通命令使用 `client.request`，Extension UI 回复使用 `client.respondToUi`。完整参数与行为见[配置与架构](docs/architecture.md)。

## 构建

```bash
mvn clean verify
mvn -Dpi.test.real=true clean verify
```

生成 SDK、sources 和 Javadoc JAR。真实 Pi 测试使用本地确定性 Provider，无需模型 API key。

[配置与架构](docs/architecture.md) · [测试与兼容边界](docs/validation.md) · [Apache License 2.0](LICENSE)
