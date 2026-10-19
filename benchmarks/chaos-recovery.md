# Worker 崩溃与租约恢复演示

后续网络别名修复、内部调用超时回归和最新故障证据见 [阶梯压测与故障诊断](2026-10-19-staircase.md#故障诊断与修复)。临时 A 实例现使用 `--use-aliases`，脚本会断言 `delivery-service` 别名存在，并在失败时保存阶段和错误报告。

这个演示回答一个实际的分布式系统问题：HTTP 请求可能已经送达接收方，但发送方进程在收到响应前死亡。此时 RelayBox 无法知道接收方是否执行了业务操作，必须在租约过期后重试，因此保证的是至少一次投递。接收方应按 `X-Webhook-Delivery` 做幂等处理。

## 已验证结果（2026-10-19，本机 Docker Desktop）

截至 2026-10-19，早期版本完成八轮故障注入：四轮使用内存演示去重，随后四轮使用 PostgreSQL 持久化去重，均来自 RabbitMQ/独立 Delivery Service 拆分前的共享数据库架构。新架构另完成一轮容器故障注入，原始报告见 [`results/chaos-5fbaef4d222d.json`](results/chaos-5fbaef4d222d.json)。

| 原始报告 | 最终状态 | 首次尝试 | 接管尝试 | 接收请求 / 业务效果 |
| --- | --- | --- | --- | --- |
| `results/chaos-da7229fe2820.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.125 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1 |
| `results/chaos-6b46f635dc1e.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.189 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1 |
| `results/chaos-6aa0ee63af0d.json` | `SUCCEEDED` | 租约过期后 `FAILED` | `SUCCEEDED`，HTTP 200 | 2 / 1 |
| `results/chaos-670596478447.json` | `SUCCEEDED` | 租约过期后 `FAILED` | `SUCCEEDED`，HTTP 200 | 2 / 1 |
| `results/chaos-4e471ffa398b.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.173 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1，PostgreSQL 去重 |
| `results/chaos-ee16129d930a.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.126 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1，PostgreSQL 去重 |
| `results/chaos-0b67d9bc936c.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.012 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1，PostgreSQL 去重 |
| `results/chaos-7bf2c96b0fa8.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.192 秒 | `SUCCEEDED`，HTTP 200 | 2 / 1，PostgreSQL 去重 |
| `results/chaos-04cc15bd082c.json` | `SUCCEEDED` | 租约过期后 `FAILED`，12.059 秒 | `SUCCEEDED`，HTTP 200，43 ms | 2 / 1，微服务拆分后复验 |

新架构报告 `chaos-04cc15bd082c.json` 显示：Delivery Service A 在接收端已收到第一次请求后被强制终止；租约 12 秒后，Compose 中的替代实例完成第二次投递。两次 HTTP 请求共用一个投递 ID，第一次尝试 `FAILED`、第二次 `SUCCEEDED`（43 ms），接收端 PostgreSQL 只记录一次演示业务效果。

脚本对每轮数据执行状态断言，异常时不会输出成功结果。曾有一轮脚本在读取投递列表时收到 HTTP 400 并安全退出；随后诊断请求成功，重新运行故障注入也通过。最新复验还直接检查了 `/dev/receiver/chaos/report`：它经 API 到 Delivery Service 读取独立数据库中的两条尝试记录，返回最终 `SUCCEEDED`、2 条接收记录、2 条尝试记录和 1 次演示业务效果。另一次接收端进程重启后，PostgreSQL 中对应投递 ID 的业务效果记录仍为 1 条。新增逻辑会对同一投递 ID 的不同事件内容返回 HTTP 409，避免静默吞掉幂等键冲突。管理台的“故障接管现场”每 1.5 秒读取一次受主密钥保护的 `/dev/receiver/chaos/report`，同时展示接收端记录和数据库尝试记录。这些演示是功能验证，不是故障恢复时间的统计压测。

同日运行独立 PostgreSQL 测试库中的集成测试，16 个测试全部通过。前端 `npm run build`、后端 Docker 镜像构建均通过。管理台报告接口实际返回 `SUCCEEDED`、2 次接收请求、2 条尝试记录和 1 次演示业务效果。

## 复现步骤

1. 在本地执行 `docker compose up --build -d`，等待 API 健康。管理台地址为 `http://localhost:3005`，进入“演示中心”查看实时状态。
2. 在项目根目录执行 `.\scripts\chaos-recovery.ps1`。也可以从任意目录用脚本的绝对路径运行；脚本会自动切换到 RelayBox 项目根目录。自定义主密钥时加上 `-MasterKey $env:RELAYBOX_MASTER_KEY`。
3. 脚本创建独立租户和端点，停止正常 Delivery Service 副本，启动 Delivery Service A，发布事件，并等演示接收端确认收到第一个 HTTP 请求。
4. 第一次 HTTP 响应会被扣住 30 秒；脚本此时强制终止 Delivery Service A，再以正常 Compose 服务启动 Delivery Service B。演示期间新副本的租约设为 12 秒，结束后恢复原来的副本数和默认配置。
5. Worker B 将过期的尝试标为失败，重新领取同一个投递任务；演示接收端对第二次请求立即返回 HTTP 200。接收端使用投递 ID 去重，只在第一次请求时应用演示业务效果。脚本检查两条尝试、最终状态、两次请求和一次业务效果，并写出 `benchmarks/results/chaos-*.json`。
6. 无论演示成功还是失败，脚本都会删除临时 A 容器，并尝试恢复原来的普通副本数量与租约配置。

## 应观察到什么

| 证据 | 预期 |
| --- | --- |
| 第一条尝试 | `FAILED`，错误包含 `worker lease expired before completion` |
| 第二条尝试 | `SUCCEEDED`，HTTP 200 |
| 投递最终状态 | `SUCCEEDED`，`attemptCount=2` |
| 接收端记录 | 两次请求共用同一个投递 ID |
| 演示业务效果 | `appliedBusinessEffects=1`，第二次请求被去重 |

演示接收端由 `TEST_RECEIVER_ENABLED=true` 控制，生产环境默认关闭。它把投递 ID 作为 PostgreSQL 主键，用 `INSERT ... ON CONFLICT DO NOTHING` 持久化一次业务效果；Worker 或接收端进程重启后，去重记录仍在。真实接收方还需将自己的业务变更与去重记录放在同一事务中。脚本会短暂停止正常的本地 Worker；请勿在生产环境或共享开发数据库运行。

简历可以说“在本机故障注入演示中验证 Worker 崩溃后的租约恢复和接收端幂等”，但不要把两次演示写成大规模故障恢复统计结论。若后续演示失败，应保留错误、容器日志和原始状态，不能把预期表格当成该轮已验证结论。
