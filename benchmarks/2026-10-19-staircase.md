# 微服务阶梯压测与故障诊断

日期：2026-10-19。本次在本机 Compose 环境定位了两个不同阶段的积压：快速接收端下的 API Outbox 交接速率，以及慢接收端下的投递执行并发。七轮正式请求共接受 12300 个事件，12297 个成功、3 个因连续三次 HTTP 503 进入死信，观测结束时没有 PENDING 或 IN_FLIGHT。

后续根据本页定位继续调优 Outbox 批次、Delivery Worker 并发和端点顺序领取索引，并在最终配置下复测 45/90/150 事件/秒。较新的结果、阶段间对照和局限见 [Outbox 与投递队列调优](2026-10-19-outbox-queue-tuning.md)。本页下方数据保留为调优前的历史证据。

## 方法

- 与 [微服务固定负载基线](2026-10-19-microservices.md) 相同主机和 Compose 架构，1 个 API、1 个 Delivery Service、各自 PostgreSQL，以及 Redis、RabbitMQ。
- 每轮独立租户、40 个端点流、40 个预热事件；正式发压 20 秒，并发上限 32，租户额度 10000 次/分钟。每轮等待投递达到成功或死信终态后再开始下一轮。
- Delivery Service：轮询 250 ms，线程池核心线程 8、最大线程 16、队列容量 500；最大投递尝试次数为 3。
- 快速接收端不注入延迟或错误；慢接收端固定延迟 250 ms；混合场景在此基础上，每第 10 次物理 HTTP 请求返回 503。它是确定性的请求序列，不是对每个事件独立随机采样。
- API P95 来自客户端成功发布请求耗时；投递 P95 来自 Delivery PostgreSQL 中成功事件的创建时间到最终投递更新时间。死信不计入该延迟分位数，单列数量。
- Outbox 与 Delivery 数据库在发压结束后依次读取，属于相邻时刻的快照，不能按原子快照精确相减。前三轮尚未采集 Outbox 数量，因此额外复测了 150 档。

## 结果

| 场景 | 输入/秒 | 接受 | 成功 / 死信 | API P95 | 成功投递 P95 | 从发压到观测完成 | 原始报告 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 快速 | 45 | 900 | 900 / 0 | 24.30 ms | 0.485 s | 20.84 s | [d8587a358060](results/d8587a358060.json) |
| 快速 | 90 | 1800 | 1800 / 0 | 22.02 ms | 5.064 s | 26.45 s | [6e5361935201](results/6e5361935201.json) |
| 快速 | 150 | 3000 | 3000 / 0 | 20.77 ms | 20.943 s | 43.52 s | [d818b81bab00](results/d818b81bab00.json) |
| 快速，补测 Outbox | 150 | 3000 | 3000 / 0 | 20.86 ms | 22.835 s | 45.58 s | [7ed057edb2dd](results/7ed057edb2dd.json) |
| 延迟 250 ms | 45 | 900 | 900 / 0 | 21.69 ms | 9.535 s | 31.27 s | [dbb9338f3659](results/dbb9338f3659.json) |
| 延迟 250 ms，每 10 次一次 503 | 45 | 900 | 899 / 1 | 22.19 ms | 14.363 s | 39.90 s | [9583158c6ed2](results/9583158c6ed2.json) |
| 延迟 250 ms，每 10 次一次 503 | 90 | 1800 | 1798 / 2 | 21.53 ms | 43.856 s | 69.95 s | [eadd5b9fbccb](results/eadd5b9fbccb.json) |

所有正式发布请求均被接受，没有 HTTP 429。混合场景两轮分别执行 999、1998 次投递，接收端分别返回 100、200 次 503。三条死信都连续三次收到 503，详见 [死信尝试证据](results/staircase-dead-letter-attempts.json)。报告顶层 `complete=false` 表示未全部成功；`delivery.complete=true` 表示全部进入成功或死信终态。这两轮不能计入“零失败”结果。

## 瓶颈判断

**快速接收端：Outbox 交接先积压。** 90 档发压结束时，1800 个已接受事件中只有 1440 个进入投递库。150 档补测在发压结束后记录到 1563 个 Outbox 事件尚未发布，最老已等待约 10.61 秒；此时 Delivery 侧快照只有 20 个 PENDING、4 个 IN_FLIGHT。

代码中 `OutboxDispatcher` 每轮最多取 20 条，并在本轮执行完成后再等待 250 ms。即使忽略数据库操作和逐条 publisher confirm 的耗时，上限也约为 80 条/秒；实际还会更低。因此当前实验支持 Outbox 调度与批量大小限制交接速率的判断。45 档能及时处理，90 档开始持续积压，具体稳定容量仍需更细的输入阶梯和更长观测。仅增加 Delivery Service 副本无法解除这个前置限制。

**慢接收端：执行并发开始限制投递。** 250 ms 延迟、45 档时，Outbox 快照只剩 1 条未发布，但投递库有 248 个 PENDING、40 个 IN_FLIGHT。默认核心线程数是 8，且线程池先使用任务队列；40 条端点流不足以填满 500 的队列并触发最大线程扩展。忽略其他开销，8 个线程处理每次 250 ms 请求约为 32 次/秒，与当前负载下积压增长的现象一致。IN_FLIGHT 包含已领取但在线程池等待的任务，不能等同于实际并发 HTTP 连接数。

加入 503 后重试占用同一执行资源，还会阻塞对应端点后续有序事件。混合 90 档结束时 Outbox 尚有 353 条未发布，投递库有 898 个 PENDING、36 个 IN_FLIGHT，两个阶段都在积压。

[容器和 RabbitMQ 样本](results/staircase-resource-samples.json)保存了快速阶梯期间的五个瞬时样本。主 RabbitMQ 队列在这些样本中没有明显堆积；死信队列原有 1 条消息，不能算成本次新增死信。采样包含 `rabbitmqctl` 的运行开销，尤其不能将其瞬时 CPU 峰值直接当作业务负载证据。没有连续 CPU、磁盘延迟或数据库等待事件采样，本次没有证明数据库已饱和。

## 故障诊断与修复

- 原来间歇性的 HTTP 400 未再次复现，根因仍未确认。API 新增参数校验和绑定异常日志；通过非法 UUID 请求验证了 HTTP 400 及对应 `MethodArgumentTypeMismatchException` 日志。
- 临时故障实例 A 原先未显式启用 Compose 服务网络别名。脚本改用 `--use-aliases`，并检查别名存在；[复验报告](results/chaos-6edcfd1dc812.json)记录了 `delivery-service` 别名、两次投递和一次业务效果。这个修复不能单独证明此前 400 的根因。
- 停止 Delivery Service 后，修复前报告查询在客户端等待 15 秒仍无 HTTP 响应，见 [修复前证据](results/service-unavailable-before.json)。内部 HTTP 客户端现设置 2 秒连接超时、5 秒响应超时，访问失败映射为 HTTP 503，并返回 `Retry-After: 2`。同一停机探测在约 4.123 秒返回 503，见 [修复后证据](results/service-unavailable-after.json)。故障脚本中的只读查询可按原逻辑重试 5xx。
- API 镜像重新编译构建通过，修复后的故障接管脚本通过。压测与故障脚本现在会为提前失败保存阶段和错误信息；所有数据卷及实验记录保留。

最终核对：API 健康为 `UP`，前端 HTTP 200，服务恢复后的故障报告为 `SUCCEEDED`、2 条尝试、1 次业务效果。Delivery Service 保持单副本，临时接收器端口 18089 已关闭。

## 复现

在项目目录启动 Compose，确认单副本及 API 健康。在一个 PowerShell 终端运行接收器：

```powershell
$env:BENCH_RECEIVER_DELAY_MS = '0'
$env:BENCH_RECEIVER_FAIL_EVERY = '0'
node scripts/benchmark-receiver.mjs
```

另一个终端依次执行，各轮等待结束：

```powershell
node scripts/benchmark.mjs --rate=45 --duration-seconds=20 --concurrency=32 --streams=40 --timeout-seconds=180
node scripts/benchmark.mjs --rate=90 --duration-seconds=20 --concurrency=32 --streams=40 --timeout-seconds=180
node scripts/benchmark.mjs --rate=150 --duration-seconds=20 --concurrency=32 --streams=40 --timeout-seconds=180
```

停止接收器后，把 `BENCH_RECEIVER_DELAY_MS` 改为 `250` 并重新启动，运行 45 档。再次停止接收器，把 `BENCH_RECEIVER_FAIL_EVERY` 改为 `10`，重新启动后运行 45 和 90 档。出现死信时命令退出码为 1，但完整 JSON 报告仍会保存。

这些是短时本机实验，快速阶梯只在 150 档额外复测一次；不能据此承诺生产容量、长期成功率或端到端 exactly-once。后续优化应先调整 Outbox 批量与调度策略，再对慢接收端场景调整有界执行并发及租约预算，并用同一组负载比较结果。
