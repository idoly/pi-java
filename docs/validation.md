# 测试与兼容边界

## 运行

```bash
mvn clean verify
mvn -Dpi.test.real=true clean verify
```

默认运行 87 个无需 Pi 的测试，跳过 20 个真实 Pi 用例。设置 pi.test.real=true 后运行全部 107 个测试，需要 PATH 中可用的 Pi。

真实 Pi 用例通过 rpc-test-extension.ts 注册本地确定性 Provider，使用离线启动，不需要模型 API key，也不调用付费模型。测试扩展只在测试源码中。

## 回归覆盖

| 测试类 | 用例数 | 主要行为 |
|---|---:|---|
| `PiConfigTest` | 11 | 默认值、字符串目录、秒数期限、CLI 选项映射与参数值、输入拒绝 |
| `PiCodecTest` | 10 | JSONL 分块、Unicode、CRLF、末尾记录、frame 限制和严格 JSON |
| `PiConnectionTest` | 10 | ID/command 关联、乱序与迟到响应、超时清理、拒绝、退出、重复关闭 |
| `PiProcessTest` | 5 | 真实管道、末尾响应、进程树回收、阻塞 stdin 与 stderr 截断 |
| `PiClientActivityTest` | 31 | 接受/settled 时序、handled、独立运行、控制请求、取消、期限、预算、关闭竞争 |
| `PiClientConcurrencyTest` | 1 | 多个 Client 的结果隔离 |
| `PiPublisherTest` | 12 | demand、条数/字节预算、回放、终态与锁外回调 |
| `PiResultTest` | 3 | 成功终态、取消、未知或缺失终态 |
| `PiThreadsTest` | 1 | daemon I/O worker |
| `PiEventTest` / `PiImageTest` | 3 | 文本增量识别与图片输入 |
| `PiClientIntegrationTest` | 20 | 真实 Pi 启动参数、会话、工具、UI、取消、故障、大响应与回放 |

PiProcessFixture 控制 PiProcessStub 子 JVM，SDK 通信通过真实 stdin/stdout；独立 socket 用于安排响应和退出。集成用例验证会话保存与重新打开、模型实际收到的系统提示顺序和工具排除、3000 个文本增量回放、17 MiB 响应、30 轮调用、图片和 Extension UI。

## 构建结果

当前源码执行 mvn -Dpi.test.real=true clean verify 的结果：

| 环境 | JVM | Pi | 失败 / 错误 / 跳过 | 结果 |
|---|---|---|---|---|
| Windows | 25 | 1.1.0 | 0 / 0 / 0 | 107 tests，BUILD SUCCESS |
| Linux/WSL | 25 | 1.1.0 | 0 / 0 / 0 | 107 tests，BUILD SUCCESS |

编译目标为 Java 17，产物为 SDK、sources 和 Javadoc JAR。GitHub Actions 配置 Linux/Windows、JVM 17/25，RPC 作业测试 Pi 1.1.0 与 npm latest；本地结果不代表远端作业或 JVM 17 执行结果，latest 是兼容探测目标。

## 覆盖边界

Pi 1.1.0 是已验证基线。模型切换、完整 fork/clone、export、真实 compaction/retry、缓存预热和认证刷新尚无完整故障矩阵。

现有回归验证功能和故障边界，不提供多小时运行、大量图片、无限会话或总内存/线程容量结论。普通 request 保留原始 JSON，不代表所有命令的每种语义均已覆盖。

使用方式见 [README](../README.md)，接口、配置和运行边界见 [配置与架构](architecture.md)。
