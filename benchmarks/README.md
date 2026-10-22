# RelayBox 本机投递压测记录

最新收尾复测（200 events/s 稳态与 2,000 事件突发）见 [2026-10-22 收尾复测](2026-10-22-revalidation.md)。历史阶梯、慢响应与 503 场景见 [微服务阶梯压测与故障诊断](2026-10-19-staircase.md)：包含 Outbox 积压、线程池并发限制、三条死信和内部调用超时修复证据。之后针对 Outbox 与有序投递领取 SQL 的优化和 45/90/150/s 复测见 [Outbox 与投递队列调优](2026-10-19-outbox-queue-tuning.md)。

测试日期：2026-10-18。以下是本机 Docker Desktop 环境的测量结果，不代表生产环境容量。

如何把本机测量扩展为目标部署的容量结论，以及按瓶颈选择扩容方案，见 [容量评估指南](../docs/production-capacity.md)。

当前 RabbitMQ 与独立 Delivery Service 架构的固定负载六轮对照结果见 [微服务架构压测记录](2026-10-19-microservices.md)。下文是旧单体/共享数据库架构的历史数据，不应用于描述当前架构。

2026-10-19 将 Compose 的 Worker 轮询间隔从 1000 ms 调整为 250 ms 后，同日对照与更高负载结果见 [轮询间隔调优记录](2026-10-19-polling.md)。下文六轮结果均为**调整前**的 1000 ms 配置，且是在引入 RabbitMQ 与独立 Delivery Service 之前取得；它们是旧版基线，不能直接代表现在的吞吐表现。

## 环境与方法

- Windows 11（10.0.26300），32 个逻辑 CPU，主机内存 31.2 GiB；Docker 可用内存 15.2 GiB。
- 1 个 API 容器、1 个 PostgreSQL 17 容器，分别使用 1 个或 2 个 Java 17 Worker 容器。每个 Worker 的配置相同，批量领取上限 20、轮询间隔 1 秒。该测试针对旧版共享数据库架构。
- 独立的本机 Node.js HTTP 接收端通过 `host.docker.internal:18089` 接收并返回 HTTP 200；它与 API 不在同一进程。
- 每轮建立独立租户和 40 个端点，每个端点只订阅自己的事件类型，以保留端点内顺序并提供 40 条可并行的投递流。租户限流设为每分钟 10000 次，避免默认配额截断本次负载。
- 每轮先对每条流发布 1 个预热事件，确认全部成功后，以每秒 45 个事件持续发送 30 秒，共 1350 个正式事件。发压客户端并发上限 24。
- API 延迟由压测客户端测量；投递延迟由 PostgreSQL 的 `webhook_event.created_at` 到 `webhook_delivery.updated_at` 计算。完成总时间包含每秒轮询数据库的观测误差。
- 每轮使用唯一事件类型，所有事件、投递、尝试记录留在数据库中便于复核；报告不包含 API Key。

## 六轮正式结果

| Worker | 轮次 | 接受 / 成功 | API P95 | 30 秒结束时成功 / 待投递 | 投递 P95 | 全部完成的观测时间 |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 1 | `81403a1161d1` | 1350 / 1350 | 21.60 ms | 600 / 750 | 36.29 s | 68.58 s |
| 1 | `462463f4ca3d` | 1350 / 1350 | 21.41 ms | 600 / 750 | 36.21 s | 68.66 s |
| 1 | `a0f49636461c` | 1350 / 1350 | 21.46 ms | 600 / 750 | 36.28 s | 68.99 s |
| 2 | `b0e8f886dd16` | 1350 / 1350 | 21.49 ms | 1190 / 160 | 4.09 s | 34.99 s |
| 2 | `6cbabb951f89` | 1350 / 1350 | 21.29 ms | 1180 / 170 | 4.07 s | 34.92 s |
| 2 | `71a97a3819a9` | 1350 / 1350 | 21.39 ms | 1178 / 172 | 4.08 s | 34.99 s |

六轮共接受 8100 个正式事件，产生 8100 次成功投递和 8100 次尝试；拒绝请求、死信和遗留待投递任务均为 0。将 Worker 从 1 个增加到 2 个后，在本测试负载下，发压结束时已完成的投递由每轮 600 个提高到 1178–1190 个；投递 P95 由 36.21–36.29 秒降至 4.07–4.09 秒。API P95 保持在 21.29–21.60 毫秒。

输入速率 45 事件/秒是**设定的负载**，不是测得的系统最大吞吐量。接收端始终快速返回 HTTP 200，因此本次测量不覆盖外部网络延迟、失败重试和极限饱和容量，也不能由此声称生产环境的成功率。

## 复现

先启动 Docker Desktop，在项目目录运行：

```powershell
$env:WORKER_POLL_INTERVAL_MS = "1000" # 旧测试配置
docker compose up --build -d app delivery-service postgres delivery-postgres rabbitmq
docker compose up -d --scale delivery-service=1 delivery-service
```

在一个 PowerShell 窗口启动独立接收端，并保持该窗口运行：

```powershell
node scripts/benchmark-receiver.mjs
```

在另一个窗口运行压测。如使用了非默认主密钥，先设置 `$env:RELAYBOX_MASTER_KEY`：

```powershell
node scripts/benchmark.mjs --rate=45 --duration-seconds=30 --concurrency=24 --streams=40 --timeout-seconds=120
docker compose up -d --scale delivery-service=2 delivery-service
node scripts/benchmark.mjs --rate=45 --duration-seconds=30 --concurrency=24 --streams=40 --timeout-seconds=120
```

每轮报告保存在 `benchmarks/results/<runId>.json`，含环境配置、原始 API 延迟样本、投递状态和数据库计算的分位数。正式对照应各重复三轮，并保持其他配置不变。

当前 Compose 版本使用 RabbitMQ 与独立 Delivery PostgreSQL；相同请求速率的复测会经过不同架构，应该保存为新基线，不能视为对本页旧版结果的严格复现。运行 `Remove-Item Env:WORKER_POLL_INTERVAL_MS` 并重建 Delivery Service 可恢复 250 ms 默认值。

## 可用于性能说明的表述

当前推荐的性能表述和证据见 [2026-10-22 收尾复测](2026-10-22-revalidation.md)。下面这段只适用于 2026-10-18 的旧版共享数据库配置，保留用于对照，不应再用于描述当前架构。

> 基于 PostgreSQL `SKIP LOCKED` 与租约实现有序 Webhook 投递；在单机 Docker Desktop 环境中，以 45 事件/秒持续 30 秒、40 条端点流进行三轮对照压测，2 个 Worker 相比 1 个 Worker 将投递 P95 从约 36.2 秒降至约 4.1 秒；8100 个正式事件均成功投递，0 死信。

引用上述数字时应明确它们对应的本机环境、快速返回 200 的接收端，以及每端点严格顺序对并行度的限制。
