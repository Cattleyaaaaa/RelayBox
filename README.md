# RelayBox

RelayBox 是一个可靠的 Webhook 事件投递服务。业务系统提交事件后，平台会匹配订阅端点、持久化投递任务、异步发送带 HMAC 签名的请求，并在失败时自动重试。

## 技术栈

- Java 17、Spring Boot 4、Maven
- PostgreSQL 17、Flyway
- PostgreSQL `FOR UPDATE SKIP LOCKED` 队列领取
- JDK `HttpClient`、HMAC-SHA256

## 本地启动

Windows 上先安装并启动 Docker Desktop（推荐 WSL 2 后端）。在项目目录打开 PowerShell，设置开发用主密钥并启动应用和 PostgreSQL：

```powershell
$env:RELAYBOX_MASTER_KEY = "local-master-key"
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

Compose 会构建应用镜像、等待 PostgreSQL 健康检查通过，再启动 RelayBox；应用启动时 Flyway 会创建表。API 地址是 `http://localhost:8080`，数据库端口映射到 `localhost:5432`，开发数据库用户名和密码均为 `relaybox`。停止容器但保留数据库数据用 `docker compose down`。部署时请设置强随机 `RELAYBOX_MASTER_KEY` 和数据库凭证。

Compose 开发环境启用了本地测试接收器：管理接口仅接受主密钥，投递目标使用容器内地址 `http://app:8080/dev/receiver/deliver`。它默认在三次请求后返回 `200`，并将最近 100 次请求留在内存中；该接收器只用于本地演示，不要在生产环境启用。Compose 将最大尝试次数设为 3、重试基准间隔设为 1 秒，以便快速演示；应用未设置这些环境变量时仍默认为 8 次和 5 秒基准间隔。

如果只想用 Docker 启 PostgreSQL、在本机运行 Java，可用 `docker compose up -d postgres`，然后执行 `mvn "-Dmaven.repo.local=.m2/repository" spring-boot:run`。

## 快速演示

先用主密钥创建租户。创建响应只返回一次租户 API Key；平台只保存它的 SHA-256 摘要。之后管理该租户的端点和事件时，都在 `X-API-Key` 中使用这个租户密钥。PowerShell 示例：

```powershell
$adminHeaders = @{ "X-API-Key" = "local-master-key" }
$tenant = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/tenants `
  -Headers $adminHeaders -ContentType "application/json" -Body '{"name":"demo-shop"}'
$headers = @{ "X-API-Key" = $tenant.apiKey }
$endpoint = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/endpoints `
  -Headers $headers -ContentType "application/json" `
  -Body '{"url":"https://example.com/webhook","eventTypes":["order.paid"]}'
$endpoint
```

创建业务事件：

```powershell
$event = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/events `
  -Headers $headers -ContentType "application/json" `
  -Body '{"type":"order.paid","idempotencyKey":"order-2026-0001-paid","payload":{"orderId":"2026-0001","amount":12900}}'
$event
```

响应里的 `eventId` 可用于查询投递记录：

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/events/$($event.eventId)/deliveries" -Headers $headers
```

端点创建响应中的 `signingSecret` 只在创建时返回。接收方按 `Base64URL 解码后的密钥` 对 `时间戳 + "." + 原始请求体` 做 HMAC-SHA256，并将小写十六进制结果与 `X-Webhook-Signature` 的 `v1=` 值比较。请求还会带 `X-Webhook-Id`、`X-Webhook-Delivery` 和 `X-Webhook-Timestamp`。

## 重试、死信和重放演示

下面的本地演示让测试接收器前两次返回 HTTP 500，第三次返回 200。先配置接收器，再为租户注册本地接收地址并发布事件：

```powershell
$adminHeaders = @{ "X-API-Key" = "local-master-key" }
$tenant = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/tenants `
  -Headers $adminHeaders -ContentType "application/json" `
  -Body ('{"name":"retry-demo-' + [guid]::NewGuid().ToString('N') + '"}')
$headers = @{ "X-API-Key" = $tenant.apiKey }
Invoke-RestMethod -Method Post -Uri http://localhost:8080/dev/receiver/config `
  -Headers $adminHeaders -ContentType "application/json" `
  -Body '{"failuresBeforeSuccess":2,"failureStatus":500}'
$endpoint = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/endpoints `
  -Headers $headers -ContentType "application/json" `
  -Body '{"url":"http://app:8080/dev/receiver/deliver","eventTypes":["order.paid"]}'
$event = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/events `
  -Headers $headers -ContentType "application/json" `
  -Body ('{"type":"order.paid","idempotencyKey":"' + [guid]::NewGuid().ToString() + '","payload":{"orderId":"demo-1001"}}')
Start-Sleep -Seconds 6
$deliveries = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/events/$($event.eventId)/deliveries" -Headers $headers
$deliveries | Format-Table
Invoke-RestMethod -Uri http://localhost:8080/dev/receiver/state -Headers $adminHeaders
```

投递记录应显示 `SUCCEEDED` 且 `attemptCount` 为 3。查看单次尝试可调用 `/api/v1/deliveries/{deliveryId}/attempts`。下面再让接收器持续返回 500，观察投递进入死信，然后恢复接收器并重放：

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/dev/receiver/config `
  -Headers $adminHeaders -ContentType "application/json" `
  -Body '{"failuresBeforeSuccess":100,"failureStatus":500}'
$deadEvent = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/events `
  -Headers $headers -ContentType "application/json" `
  -Body ('{"type":"order.paid","idempotencyKey":"' + [guid]::NewGuid().ToString() + '","payload":{"orderId":"demo-1002"}}')
Start-Sleep -Seconds 6
$deadDelivery = (Invoke-RestMethod -Uri "http://localhost:8080/api/v1/events/$($deadEvent.eventId)/deliveries" -Headers $headers)[0]
$deadDelivery | Format-List
Invoke-RestMethod -Method Post -Uri http://localhost:8080/dev/receiver/config `
  -Headers $adminHeaders -ContentType "application/json" `
  -Body '{"failuresBeforeSuccess":0,"failureStatus":500}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/deliveries/$($deadDelivery.id)/replay" -Headers $headers
Start-Sleep -Seconds 2
Invoke-RestMethod -Uri "http://localhost:8080/api/v1/events/$($deadEvent.eventId)/deliveries" -Headers $headers | Format-Table
```

Compose 演示默认三次失败后标记为 `DEAD`，然后能在不丢失旧尝试记录的情况下重放。配置、状态接口都需要 `X-API-Key` 主密钥。

## 集成测试

集成测试会启动完整 Spring Boot 应用和独立 PostgreSQL 测试库，覆盖幂等键、自动重试、死信重放和租户隔离。首次运行会下载 Maven 依赖：

```powershell
docker compose --profile test run --rm integration-tests
```

测试数据库与开发数据库分开。测试 PostgreSQL 对外映射到 `127.0.0.1:55432`，方便需要时从 IDE 连接；测试结束后可用 `docker compose stop postgres-test` 停止它，不会影响正在运行的 RelayBox 开发服务。

仓库包含 GitHub Actions 流水线：推送分支或创建 PR 时会启动独立 PostgreSQL 服务并运行相同的集成测试。把项目推送到 GitHub 后即可在 Actions 页面查看结果。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/v1/tenants` | 使用主密钥创建租户，并签发一次性 API Key |
| `POST` | `/api/v1/endpoints` | 注册订阅端点，返回一次性签名密钥 |
| `GET` | `/api/v1/endpoints` | 列出端点 |
| `POST` | `/api/v1/events` | 发布事件；同一幂等键只创建一次事件 |
| `GET` | `/api/v1/events/{eventId}/deliveries` | 查看事件的投递状态 |
| `GET` | `/api/v1/deliveries/{deliveryId}/attempts` | 查看逐次投递尝试、状态码与耗时 |
| `POST` | `/api/v1/deliveries/{deliveryId}/replay` | 重放已完成或进入死信的投递 |
| `GET` | `/actuator/health` | 健康检查 |
| `GET` | `/actuator/metrics/relaybox.delivery.queue.depth` | 查看待处理、执行中和死信队列深度 |
| `GET` | `/actuator/metrics/relaybox.delivery.attempts` | 查看投递成功、重试和死信计数 |

## 投递语义

每个租户使用独立 API Key，数据库只保存密钥摘要。事件幂等键按租户唯一；端点列表、事件检索和重放都带租户条件，避免跨租户读写。投递任务和事件在同一数据库事务内落库。Worker 通过行锁跳过其他实例已领取的任务，领取时设置两分钟租约，并在同一事务写入尝试记录；进程异常退出后，租约到期的任务和未完成尝试会被恢复、留痕。接收端返回 2xx 才标记成功，其他状态码或网络错误按指数退避重试，最多尝试 8 次，随后标记为 `DEAD`。每次尝试都会保留状态、HTTP 状态码、错误和耗时；Micrometer 指标提供队列深度、结果计数和尝试耗时分布。网络调用无法提供严格的端到端 exactly-once 保证，因此接收方应使用投递 ID 做幂等处理。

## 后续可扩展

- 项目级 API Key、配额与限流策略
- 多队列分区、批量投递和运维管理界面
- Prometheus 指标、结构化审计日志和压测报告
- 签名密钥轮换、加密存储及网络出口策略
