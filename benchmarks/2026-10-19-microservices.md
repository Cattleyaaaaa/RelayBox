# RelayBox 微服务架构本机压测

后续更高输入速率、慢响应与 503 场景见 [阶梯压测记录](2026-10-19-staircase.md)。本页保留此前 45 事件/秒固定负载基线。

测试日期：2026-10-19。此结果是固定负载下的本机对照，不代表生产环境容量或系统最大吞吐量。

## 环境与方法

- Windows 11（10.0.26300），32 个逻辑 CPU、31.2 GiB 主机内存；Docker Desktop 可用 32 CPU、15.2 GiB 内存。
- Compose 部署 Event API、RabbitMQ、Redis、API PostgreSQL 和独立 Delivery PostgreSQL；对照组分别运行 1 个或 2 个 Delivery Service 副本。Delivery Service 轮询间隔 250 ms，默认并发配置不变。
- 独立 Node.js 24.18 接收器运行在宿主机，通过 `host.docker.internal` 接收投递并立即返回 HTTP 200。
- 每轮创建独立租户和 40 个端点，先发送 40 个预热事件，再以设定速率 45 事件/秒发送 30 秒（1350 个正式事件），客户端并发上限 24。租户限额为 10000 次/分钟。
- 每轮保存原始 API 延迟样本；投递延迟取 Delivery PostgreSQL 中事件 `created_at` 到投递 `updated_at` 的时间差。正式事件完成状态从 Delivery Service 自有数据库读取。

## 三轮重复结果

| Delivery 副本 | 报告 | 接受/成功 | API P95 | 投递 P95 | 全部完成观测时间 | 发压结束时待处理 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | [`bd73241cfa0d`](results/bd73241cfa0d.json) | 1350 / 1350 | 23.91 ms | 457.74 ms | 30.82 s | 0 |
| 1 | [`d211e8827e9c`](results/d211e8827e9c.json) | 1350 / 1350 | 23.19 ms | 452.84 ms | 30.83 s | 0 |
| 1 | [`a226a7a7562c`](results/a226a7a7562c.json) | 1350 / 1350 | 23.08 ms | 456.87 ms | 30.91 s | 0 |
| 2 | [`3b82d86a9bcd`](results/3b82d86a9bcd.json) | 1350 / 1350 | 22.99 ms | 452.40 ms | 30.87 s | 8 |
| 2 | [`eca2d6500a9b`](results/eca2d6500a9b.json) | 1350 / 1350 | 23.39 ms | 456.58 ms | 31.01 s | 0 |
| 2 | [`ba7da311ddce`](results/ba7da311ddce.json) | 1350 / 1350 | 22.73 ms | 457.27 ms | 30.93 s | 3 |

六轮一共接受并成功投递 8100 个正式事件，拒绝 0、死信 0；双副本有两轮在停止发压时还剩 8 和 3 个待处理任务，均在观测窗口内完成。单副本组 API P95 范围为 23.08–23.91 ms、投递 P95 为 452.84–457.74 ms；双副本组分别为 22.73–23.39 ms 和 452.40–457.27 ms。

在 45 事件/秒这一负载下，两个副本没有显示出可测的投递延迟改善。单副本已能跟上该输入速率；这组数据说明当前场景没有证明需要横向扩容，不能据此声称副本翻倍带来吞吐提升或找到了最大容量。

## 结论边界

接收器在同一台主机上快速返回 200，没有模拟真实外部网络、慢接收端、HTTP 错误或 TLS 验签开销。该基准验证的是当前 Compose 架构在设定输入速率下的端到端落库与投递延迟，不是饱和压测、长期稳定性测试或生产容量承诺。若要找出容量拐点，应逐级增加输入速率和持续时间，并同时监控 API PostgreSQL、Delivery PostgreSQL、RabbitMQ 堆积、CPU/内存、拒绝率和端到端延迟。

崩溃恢复的独立故障注入报告见 [`chaos-04cc15bd082c.json`](results/chaos-04cc15bd082c.json)，并额外验证了管理台故障报告接口能从独立 Delivery Service 数据库读回两条尝试；复现脚本为 [`chaos-recovery.ps1`](../scripts/chaos-recovery.ps1)。
