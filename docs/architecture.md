# 架构说明

## 总体结构

```text
Java application
        |
        v
PiClient / PiActivity
        |
        v
PiClientImpl ---- PiActivityImpl
        |
        v
PiConnection
        |
        v
PiTransport
        |
        v
PiProcess
        |
        v
pi --mode rpc

PiToolBridge ---- generated pi Extension
```

## 组件职责

| 组件 | 职责 |
|---|---|
| `PiClientFactory` | 创建 Tool bridge、进程、连接和 Client，并在启动失败时清理资源 |
| `PiClientImpl` | 管理 Client 状态、当前 Activity 和全局事件流 |
| `PiActivityImpl` | 管理 Prompt acceptance、Activity timeout、事件聚合和最终结果 |
| `PiConnection` | 生成 request id、关联 response、处理拒绝和 RPC timeout |
| `PiTransport` | 定义连接层需要的发送、接收和退出接口 |
| `PiProcess` | 管理子进程、stdin、stdout、stderr 和关闭 |
| `PiJsonCodec` | 编解码 RPC JSON |
| `PiJsonlFramer` | 按 LF 分帧并限制单条消息大小 |
| `PiPublisher` | 为每个订阅者维护非阻塞的有界事件队列 |
| `PiToolBridge` | 将 pi Tool 调用转发到 JVM 方法 |
| `PiToolDefinition` | 发现 Tool、生成参数 Schema 并绑定调用参数 |

## 请求流程

1. `PiClient` 创建 `PiCommand`。
2. `PiConnection` 分配 request id，并登记 pending request。
3. `PiProcess` 将命令编码为一行 JSON 写入 stdin。
4. stdout 消息经过 JSONL 分帧和 JSON 解码。
5. 带 request id 的 response 完成对应 request；其他消息作为事件发布。

`PiConnection` 校验 response 的 request id 和 command。pi 返回 `success: false` 时产生 `PiRequestRejectedException`；超过配置时间未收到 response 时产生 `PiRequestTimeoutException`。

## Activity 生命周期

```text
prompt
  |
  +-- accepted()
  |
  +-- events / steer / follow_up / abort
  |
  +-- agent_settled
          |
          +-- completion()
```

`PiActivityImpl` 收集 `message_end` 中的消息和最终 Assistant 文本。Activity timeout 会请求 abort，并将 Client 状态设置为 `FAILED`。

Client 状态：

```text
STARTING -> IDLE -> ACTIVE -> IDLE
                  -> FAILED

IDLE / ACTIVE / FAILED -> STOPPING -> TERMINATED
```

## 并发与事件

一个 Client 同时只运行一个 Activity。不同 Client 可以并发运行。

`PiPublisher` 为每个订阅者提供独立的有界队列。慢订阅者超过容量时只终止该订阅，避免阻塞读取 pi stdout。`PiBufferedPublisher` 在 Activity 第一次订阅前缓存有限数量的早期事件。

进程读写和事件分发使用共享 daemon worker；RPC 和 Activity timeout 使用单独的 scheduler。

## Java Tool

`PiToolBridge` 启动只监听 `127.0.0.1` 的 HTTP 服务，并生成一个临时 pi Extension。Extension 使用随机 Bearer Token 调用 Java 端。

Tool 注册时会校验方法、参数名和支持的参数类型。调用参数经过 Schema 和 Java 类型转换后执行目标方法，业务异常不会直接返回给 pi。

Client 关闭时，HTTP 服务和临时 Extension 文件一起清理。

## 资源策略

`PiProcessConfig` 将 `PiResourcePolicy` 转换为 pi 启动参数：

| 策略 | 参数 |
|---|---|
| `ISOLATED` | `--no-approve` 和各类 `--no-*` 资源参数 |
| `GLOBAL_ONLY` | `--no-approve` |
| `TRUST_PROJECT` | `--approve` |

通过 `@PiTool` 注册的临时 Extension 始终使用显式 `--extension` 加载。
