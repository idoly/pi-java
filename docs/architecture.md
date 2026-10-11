# 配置与架构

Java 应用通过 PiClient 管理一个 Pi RPC 子进程，Pi 执行 Agent Loop、模型调用、工具和会话存储。

## 公开接口

| 入口 | 行为 |
|---|---|
| `PiClient.open()` / `open(String)` / `open(PiConfig)` | 使用当前目录、指定目录或完整配置启动 Pi，并用 get_state 确认就绪 |
| `client.prompt(text)` / `prompt(text, seconds)` | 创建一次 Activity，默认期限 600 秒 |
| `client.prompt(text, images, seconds)` | 携带 PiImage 输入创建 Activity |
| `client.request(command, arguments, seconds)` | 普通 RPC，省略期限时使用配置中的 requestTimeout |
| `client.events()` / `activity.events()` | Flow 事件流，需要订阅者提供 demand |
| `activity.steer` / `followUp` / `prompt` | 控制当前运行；prompt 执行扩展命令，普通文本采用 steering |
| `client.abort()` / `activity.abort()` | 清除 queued 输入并请求取消；Client 也可取消扩展独立启动的运行 |
| `client.respondToUi(id, values)` | 沿用对话 ID 回复 UI，只等待写入 |
| `client.close()` | 关闭连接并等待进程清理，重复调用等待同一次清理 |

工作目录必须是存在的磁盘目录。相对目录基于 Java 进程当前目录解析；classpath 中的资源需由应用解析或提取到磁盘。公开期限使用正数 long 秒值。

## 配置

`PiConfig.builder()` 默认当前目录，`builder(String)` 指定目录。Builder 收集启动与运行选项，build 返回不可变配置。

| SDK 选项 | 默认值 | 作用 |
|---|---|---|
| `command` | 平台 Pi 命令 | 可执行文件或包装器的参数列表 |
| `requestTimeout` | 15 秒 | RPC response 等待期限 |
| `retainMessages` | false | 是否保存 Activity 的完整消息 |
| `activityResultLimit` | 16 MiB | 流式/最终文本预算；保留模式还累计消息 JSON |
| `eventBufferCapacity` | 1024 条 | 每个事件订阅、首次回放和内部入站的积压条数 |
| `eventBufferLimit` | 8 MiB | 每个公开事件订阅和首次回放的 JSON 字节预算 |
| `rpcLineLimit` | 16 MiB | 单条入站 JSONL 大小 |
| `diagnostics` | 关闭 | 有界异步诊断回调 |
| `resourcePolicy` | ISOLATED | 资源加载策略 |
| `environment` | 空覆盖 | 子进程环境变量覆盖，SDK 设置 PI_SKIP_VERSION_CHECK=1 |
| `codemode` / `toolSearch` | false | 加载对应 builtin 扩展，并添加 +codemode / +tool_search 工具选择 |

Pi 启动选项按 CLI 语义映射：

| Builder 方法 | Pi 参数 |
|---|---|
| `provider` / `model` / `apiKey` / `thinking` | `--provider` / `--model` / `--api-key` / `--thinking` |
| `models(String...)` | `--models`，逗号分隔的模型模式 |
| `noSession` / `session` / `sessionId` / `sessionDir` / `name` | `--no-session` / `--session` / `--session-id` / `--session-dir` / `--name` |
| `fork` / `continueSession` | `--fork` / `--continue` |
| `tools(String...)` / `excludeTools(String...)` | `--tools` / `--exclude-tools`，保留模式和 +name/-name 语义 |
| `noTools` / `noBuiltinTools` / `noMcp` | `--no-tools` / `--no-builtin-tools` / `--no-mcp` |
| `systemPrompt` / `appendSystemPrompt(String...)` | `--system-prompt` / 重复 `--append-system-prompt` |
| `extensions` / `skills` / `promptTemplates` / `themes` | 重复 `--extension` / `--skill` / `--prompt-template` / `--theme` |
| `noExtensions` / `noSkills` / `noPromptTemplates` / `noThemes` / `noContextFiles` | 对应的 `--no-*` 开关 |
| `offline` | `--offline` |

默认 `noSession(false)` 使用 Pi 的会话保存，true 使用内存会话。同类重复选项按调用顺序累积；布尔 false 撤回该方法添加的开关。`args(List<String>)` 追加原始 Pi 或扩展参数，参数值可为空或以连字符开头。SDK 保留 mode、print/help/version/export/list-models 和交互式 resume 入口，模型、工具和会话参数的具体值由 Pi 校验。

资源策略映射如下：

| 策略 | 启动参数 |
|---|---|
| ISOLATED | --no-approve、--no-context-files、--no-extensions、--no-skills、--no-prompt-templates、--no-themes |
| GLOBAL_ONLY | --no-approve |
| TRUST_PROJECT | --approve |

进程参数依次组装为：启动命令、RPC mode、会话/资源/模型选项、Codemode 与 tool search、其他命名选项、原始 args。Pi 决定重复参数的最终含义。显式资源可在禁用自动发现时加载，布尔 false 不撤销资源策略产生的开关。资源策略和工作目录不构成操作系统沙箱。

## 生命周期与结果

Client 状态为 STARTING、IDLE、ACTIVE、STOPPING、TERMINATED、FAILED。新 Activity 要求 IDLE；new_session、switch_session、fork、clone、compact 和 raw bash 同样要求空闲，并在等待 response 时阻止新 Activity。

`accepted()` 返回输入处理结果。`handled` 输入可直接完成；普通输入等待 `agent_settled` 和已提交控制请求的结果。扩展独立启动的运行可让 Client 保持 ACTIVE，即使对应输入已 handled。

结果文本来自最终 assistant message_end。`PiResult` 保留 disposition、stopReason、errorMessage、aborted 及可选消息。`successful()` 接受未取消的 handled、stop、toolUse，缺失或未知终态不判成功。

根包中的 RequestTimeoutException、ActivityTimeoutException、RequestRejectedException、ProcessExitException 均继承 core.PiException。明确拒绝可以释放输入；接受结果未知、活动期限、结果超额或迟到 queued 控制输入使 Client FAILED，需要关闭重建。SDK 不重放请求，取消不回滚已发生的副作用。

## 事件与 Extension UI

Client 事件流在订阅前不保留记录，应在请求前订阅。Activity 首次订阅获得有限回放，后续订阅只看新事件。条数或字节超额通过 onError 通知并清除积压；公开事件流溢出本身不使 Activity 结果失败。原始 PiEvent/PiResponse JSON 应只读使用。

UI 对话通过 extension_ui_request 事件交付。select/input/editor 用 value，confirm 用 confirmed，取消用 cancelled=true。respondToUi 只等待写入，不注册普通 response。通知无需回复；Pi 处理带 timeout 的对话，应用负责回复或取消没有 timeout 的对话。

## 内部结构与通信

| 类型 | 职责 |
|---|---|
| `PiClient` / `PiActivity` | session 所有权与一次输入的状态 |
| `PiConfig` / `PiResult` | 配置快照与结果 |
| `PiConnection` | Request 值、ID 关联、响应期限和 UI 写入 |
| `PiProcess` | 进程、管道、出站队列和清理；Config 保存启动选项，Write 绑定请求与 Future |
| `PiCodec` | LF 分帧、JSON 编解码、结构校验和字节计数 |
| `PiPublisher` | 有界异步分发；Entry 绑定记录和权重，Subscription 管理 demand 与队列 |
| `PiThreads` | 共享 daemon I/O、callback 和 scheduler 执行器 |
| `protocol` 消息值 | PiImage、PiEvent、PiResponse |

Connection 先登记 ID 和 deadline，再将请求交给 writer。writer 逐条写入 JSON 与 LF，response 按 ID 完成等待并校验 command/success，完成回调移除登记和取消 deadline。

stdout 持续读取字节，Codec 按 LF 分帧并接受 CRLF，拒绝尾随 JSON 值和垃圾。未知事件保留原始 JSON；记录经内部发布器串行交给 Connection，再更新 Client/Activity。正常流完成先等待 stdout EOF 和入站队列排空，再通知剩余请求进程退出。

Client 与 Activity 共用 lifecycle 锁协调所有权，公开完成阶段经 callback executor 通知。Connection 同步请求登记与关闭。Process 在短锁内取得清理所有权，在等待与回收阶段共用清理结果。

Publisher 锁保护回放、终态和订阅快照，Subscription 锁保护队列、demand 与 drain 状态。两层锁不嵌套；计量、诊断、任务提交和用户回调在发布器状态锁之外。每个订阅回调串行，并发 publish 的跨线程顺序不定义。

## 资源边界

内部入站字节预算取 max(rpcLineLimit, eventBufferLimit)，出站队列为 256 条。Pi stdout 背压不会由 Java Flow demand 直接控制；SDK 用本地队列隔离慢订阅。JSON 字节预算不包含全部 Java 对象开销，也不是所有 Client、线程或进程的总资源上限。

diagnostics 为 64 条/128 KiB，单条诊断最多 4096 字符；stderr 每行保留前 2048 字符，退出短尾最多 8 行。阻塞、异常或溢出可停用诊断订阅，stderr 可能含上游敏感输出。

共享 cached pools 没有硬线程上限，应用需限制 Client 并发和阻塞回调。关闭会回收观察到的进程树，已脱离观察范围的服务不在回收保证内。

调用示例见 [README](../README.md)，测试范围见 [验证说明](validation.md)。Pi 协议参考：[RPC](https://pi.dev/docs/latest/rpc)、[RPC 命令](https://pi.dev/docs/latest/rpc-commands)、[JSON 事件](https://pi.dev/docs/latest/json)、[Extension UI](https://pi.dev/docs/latest/rpc-extension-ui)。
