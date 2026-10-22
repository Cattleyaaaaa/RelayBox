# 2026-10-22 收尾复测：200 events/s 稳态与突发

本页记录 retention 完成后的收尾复测，用于替换 README 中过时的 45 events/s 表述。它是**本机 Docker Desktop 的固定负载结果**，不是生产容量或最大吞吐承诺。

## 环境

- Windows 11（10.0.26300），32 个逻辑 CPU，主机内存 31.2 GiB；Docker Desktop 可用 32 CPU、15.2 GiB 内存。
- Compose：1 个 Event API、1 个 Delivery Service、API PostgreSQL、Delivery PostgreSQL、RabbitMQ、Redis；API 镜像 `relaybox:local`，Delivery 镜像 `relaybox-delivery:local`。
- API Flyway v12，Delivery Flyway v4；Outbox 批量 40、轮询 100 ms；Delivery Worker 批量 40、并发 16、最大并发 32、轮询 100 ms、租约 120 s。
- 独立 Node.js 24.18 接收端运行在宿主机，通过 `host.docker.internal:18089` 接收，立即返回 HTTP 200（0 ms 延迟、0 失败）。
- 每轮创建 1 个独立租户、40 个端点，每个端点只订阅自己的事件类型；租户配额 10,000 请求/分钟，压测脚本先发 40 个预热事件。

## 结果

| 场景 | 报告 | 接受 / 成功 | API P95 | 投递 P95 | 发压结束时积压 | 全部完成 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 200 events/s × 30 s | [`01e4ce67ed4b`](results/01e4ce67ed4b.json) | 6,000 / 6,000 | 16.02 ms | 239.83 ms | 0 | 30.7 s |
| 2,000 事件突发 | [`26ef00b79bd7`](results/26ef00b79bd7.json) | 2,000 / 2,000 | 82.21 ms | 5,960.58 ms | 38 PENDING | 10.02 s |

补充分位数：

- 稳态：API P50/P99 = 9.68 / 21.80 ms；投递 P50/P99 = 175.85 / 258.92 ms；尝试 6,000 次，死信 0。
- 突发：提交 2,000 个事件耗时 3.24 s，平均 617.64 事件/秒；API P50/P99 = 48.30 / 104.41 ms；投递 P50/P99 = 3,513.91 / 6,187.57 ms；发压结束时 38 个 PENDING，最终全部成功。

## 解读

1. **200 events/s 是本次验证过的稳态档位。** 30 秒内 6,000 个事件全部接受并成功投递，发压结束时没有积压；投递 P95 约 240 ms，主要是在 Outbox、RabbitMQ、Inbox 和 Worker 队列中的等待。
2. **突发能力高于稳态。** 2,000 个事件以约 618 events/s 提交时全部被接受，但投递 P95 被拉长到约 6 秒，说明入口可以短时吸收超过持续投递能力的流量，代价是排队。
3. **历史调优记录给出饱和趋势。** 2 个租户、40 条流下，250 events/s 的 5,000 个事件仍全部成功；275 events/s 开始出现 Outbox 未发布积压，400 events/s 投递 P95 约 14.95 秒。见 [`2026-10-19-outbox-queue-tuning.md`](2026-10-19-outbox-queue-tuning.md)。
4. **限流会截断更高突发。** 单租户 10,000 请求/分钟加 10 秒突发桶，长时间超过约 200 events/s 会返回 429；这不是投递失败，而是入口保护生效。

## 复现

```powershell
docker compose up -d app delivery-service
node scripts/benchmark-receiver.mjs
```

另一个窗口：

```powershell
# 稳态：200 events/s × 30 s = 6,000
node scripts/benchmark.mjs --rate=200 --duration-seconds=30 --concurrency=16 --streams=40 --timeout-seconds=240

# 突发：2,000 个事件，客户端并发 32
node scripts/benchmark.mjs --count=2000 --concurrency=32 --streams=40 --timeout-seconds=180
```

报告写入 `benchmarks/results/<runId>.json`，包含环境、API 延迟样本、投递 P50/P95/P99、发压结束积压和 Outbox 指标。

## 边界

- 接收端在同一台主机、立即返回 200，没有模拟真实公网延迟、TLS、慢接收端、429/5xx 或重试风暴。
- 每个场景只跑了 1 轮，不能用来说明统计显著性；历史记录中的重复轮次用于固定负载对照。
- 没有采集容器 CPU、内存、PostgreSQL 锁等待和 RabbitMQ 堆积曲线；“200 events/s 稳态”只表示该配置下 30 秒内没有积压。
- 不是生产容量承诺；生产需要在目标 CPU、磁盘、网络、数据库规格下重新做阶梯负载、soak 和故障注入。
