# pi-java 集成指南

## 1. 运行条件

应用运行时需要：

- JDK 17 或更高版本；JDK 21+ 自动使用虚拟线程，CI 验证 JDK 17 和 25
- 与目标平台匹配的 Node.js
- 已安装的 `@earendil-works/pi-coding-agent`
- Provider API key 或 pi 支持的认证配置

pi-java 不把 Node.js 或 pi CLI 打进 JAR，也不通过 `org.mvnpm` classpath artifact 启动 pi。应用部署时必须让 `node` 和 `pi` 可从宿主 `PATH` 执行。

推荐固定版本并在 CI 中验证：

```bash
node --version
pi --version
```

## 2. Maven 依赖

```xml
<dependency>
    <groupId>io.github.idoly</groupId>
    <artifactId>pi-java</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

pi-java 使用 Jackson Databind，不需要 Gson，也不要求 Jackson JDK 8 或 Java Time module。

## 3. 部署 pi

开发机可以使用 npm：

```bash
npm install --global @earendil-works/pi-coding-agent@0.84.2
```

服务端更适合把 Node 和 `node_modules` 放进版本化运行目录：

```text
runtime/
├── node/                  platform-specific Node distribution
└── app/
    ├── package.json
    ├── package-lock.json
    └── node_modules/
        └── @earendil-works/pi-coding-agent/
```

示例 `package.json`：

```json
{
  "private": true,
  "dependencies": {
    "@earendil-works/pi-coding-agent": "0.84.2"
  }
}
```

CI 使用 `npm ci --omit=dev`，并为 Linux、macOS、Windows 分别装配 Node。将项目的 `node_modules/.bin` 加入服务进程 `PATH`；不要在不同 OS 间复制含原生依赖的 `node_modules`。

### 3.1 CLI 示例

项目不发布 CLI、fat JAR 或 `Main-Class`。one-shot、interactive、Session 恢复、退出码和 shutdown hook 的参考实现位于 [PiCliExample.java](./src/test/java/io/github/idoly/pi/example/PiCliExample.java)。它参与测试编译但不进入库、sources 或 javadoc JAR。

示例不注册默认 Java Tool，使用宿主 pi 的正常工具集。生产应用应按自己的部署、参数、日志和生命周期规则封装公开 Java API；业务 `@PiTool` 只由应用显式传入。

## 4. 创建 Client

```java
PiClient client = PiClient.open(workspace);
```

每个工作区创建独立 Client。不要把一个 Client 作为跨工作区全局单例，也不要并发提交多个 prompt。

框架继承宿主环境，使 `node`、`pi`、Provider 凭据和 `~/.pi/agent` 正常工作。provider、model 和 Session 存储均采用宿主 pi settings，不在 Java 侧重复配置。

## 5. Prompt 与事件

```java
try (client) {
    PiActivity activity = client.prompt(PiPrompt.text("Review the current changes"));

    PiResponse accepted = activity.accepted().toCompletableFuture().join();
    PiResult result = activity.completion().toCompletableFuture().join();

    System.out.println(result.text());
}
```

`accepted()` 只表示 prompt RPC 被接受，`completion()` 在 `agent_settled` 后完成。需要流式 UI 时订阅 `activity.events()`；需要进程级观察时订阅 `client.events()`。

RPC request timeout 固定为 15 秒，进程关闭宽限固定为 3 秒。`PiPrompt.text(...)` 的 activity timeout 默认为 10 分钟；需要不同值时直接构造 `PiPrompt`。

## 6. 固定资源边界

所有 RPC 进程固定使用：

```text
--no-approve
--no-context-files
--no-extensions
--no-skills
--no-prompt-templates
--no-themes
```

没有信任开关和资源 API。即使全局 `defaultProjectTrust` 或历史 trust 记录允许项目资源，`--no-approve` 也会忽略 `.pi/settings.json`、项目 packages、项目 system prompts 和项目 Extension；其余 `--no-*` 参数关闭 context files 及全局/项目资源 discovery。唯一例外是 pi-java 通过 CLI `--extension` 精确加载的内部 `@PiTool` adapter。pi 官方参数解析明确说明 `--no-extensions` 不阻止显式 `--extension` 路径。框架不传 `--tools`，内置 Tool 由宿主 pi 的 `defaultTools` 决定；未配置时 pi 默认启用 `read,bash,edit,write`。

## 7. Java Tool

```java
public final class TicketTools {
    private final TicketService service;

    public TicketTools(TicketService service) {
        this.service = service;
    }

    @PiTool(
            name = "create_ticket",
            label = "Create ticket",
            description = "Create an internal issue ticket"
    )
    public Ticket create(
            @PiToolParameter(
                    name = "request",
                    description = "Ticket fields"
            ) CreateTicketRequest request
    ) {
        authorizeCurrentUser();
        return service.create(request);
    }
}
```

注册：

```java
PiClient client = PiClient.open(workspace, new TicketTools(ticketService));
```

框架自动完成 Schema、临时 Extension、随机 loopback 端口和每 Client Token。pi 自动启用该显式 Extension 注册的 Tool。

集成要求：

- Tool 对象支持并发调用
- Activity abort 不保证终止已经进入的 Java 方法
- 数据库、HTTP 等下游调用设置自己的 timeout 和取消策略
- 写操作在业务层实现授权和幂等
- 不依赖 localhost 作为授权依据
- 不在异常消息中放入密钥或敏感数据
- 使用同步方法，不返回 `CompletionStage`
- `Map` 使用 `String` key
- 可选顶层参数使用引用类型

RPC 进程不加载 Skills、Prompt Templates、themes 或普通 Extensions；只为当前 JVM 业务方法使用 `@PiTool`。

## 8. Session 与关闭

Session 存储位置由宿主 pi settings 决定，应确保其持久化且可写。`get_state` 同时返回 `sessionId` 和 `sessionFile`：

```java
PiResponse state = client.request("get_state").toCompletableFuture().join();
String sessionId = state.data().path("sessionId").asText();
Path sessionFile = Path.of(state.data().path("sessionFile").asText());
```

`sessionId` 可用于业务关联和展示，但 pi `0.84.2` 的 `switch_session` 只接受 `sessionPath`。跨 Client 或 JVM 重启复用时必须保存 `sessionFile`：

```java
client.request("switch_session", Map.of(
        "sessionPath", sessionFile.toAbsolutePath().toString()));
client.request("get_entries", Map.of("since", cursor));
```

新建上下文使用 `client.request("new_session")`。Session 切换要求 Client IDLE。应用关闭、工作区释放或健康检查失败时必须调用 `close()`，建议使用 try-with-resources。

进程异常退出时，pending request 和 active Activity 会失败。上层可以创建新 Client，但不应假设旧进程中的未确认写操作可以安全重试。

## 9. 大量并发 Client

不同工作区可以并发持有独立 Client，但同一 Client 仍然只允许一个 active Activity。批量创建时必须保证部分启动失败也能关闭已创建的 Client：

```java
List<PiClient> clients = new ArrayList<>();
try {
    for (Path workspace : workspaces) {
        clients.add(PiClient.open(workspace));
    }
    runWork(clients);
} finally {
    clients.forEach(PiClient::close);
}
```

运行时线程策略：

- 所有 Client 共享 Worker Executor 和 timeout Scheduler。
- JDK 21+ 自动使用虚拟线程，JDK 25 已通过真实 pi 验证，推荐用于大量 Client。
- JDK 17 使用共享且无界的 daemon cached pool，只适合并发受控的兼容运行。
- 每个 pi 进程的 writer、stdout 和 stderr 是独立 task。
- Tool callback 和事件分发使用同一个共享 Worker。
- 关闭单个 Client 不会停止共享执行器。

虚拟线程消除了大量 Client 对平台线程数量的线性占用，但每个 Client 仍对应一个 Node/pi OS 进程。容量测试应覆盖：

- 单个 pi 进程的稳定内存和峰值内存
- 操作系统进程数、文件描述符或 Windows Handle 上限
- Provider 并发限制、速率限制和失败重试策略
- Session 与工作区磁盘吞吐
- Java Tool 的并发安全和下游连接池容量

不要在没有并发上限的情况下根据外部请求直接创建 Client。应用应在业务层设置 Client 数量上限、排队策略和空闲回收策略；pi-java 不内置跨工作区进程池。

## 10. 容器与服务部署

建议：

- 使用非 root 用户
- 挂载工作区和宿主用户默认的 `~/.pi/agent`（包含 settings 与 Session）
- 确保服务进程 `PATH` 中包含 `node` 和 `pi`
- 限制容器 CPU、内存和进程数
- 只注册审核过的 Java Tool
- 配置 `System.Logger` 后端收集 `io.github.idoly.pi.process` 的 DEBUG 日志
- readiness 检查实际打开并关闭一个 Client；`open()` 已执行 `get_state`
- 设置容器进程数和文件句柄上限
- shutdown hook 中关闭所有 Client

`@PiTool` callback 只监听容器内 `127.0.0.1`，不需要暴露端口或配置 Service。

## 11. 测试与升级

普通验证：

```bash
mvn clean verify
```

构建要求 JDK 17 或更高版本，并始终使用 `--release 17` 生成 classfile major version 61。`UnsupportedClassVersionError` 中的 `only recognizes ... 52.0` 表示宿主 `PATH` 命中了 Java 8，应使用 `where java` 或 `which java` 定位并修正。GitHub Actions 分别在 Temurin 17 和 25 上执行普通验证；线程策略测试确认 JDK 21+ 实际使用虚拟线程。发布仅包含普通、sources 和 javadoc JAR，不包含 CLI 或 fat JAR，并采用 Apache-2.0。首次正式发布后使用 `mvn verify -Dpi.compat.version=<旧版本>` 检查公开 API 兼容性。

真实 pi 验证：

```bash
mvn \
  -Dpi.test.real=true \
  -Dtest=PiToolRealPiSmokeTest test
```

升级 pi 时至少验证：

1. `get_state` RPC。
2. prompt acceptance 和 `agent_settled`。
3. Session 新建与切换。
4. 未知事件保留。
5. 固定 `--no-*` 资源隔离参数。
6. 生成的 `@PiTool` Extension 加载与 callback 调用。
7. 进程异常退出和 Client 清理。

先在 CI 固定新版本运行契约测试，再更新生产 runtime；不要依赖 npm 浮动版本。
