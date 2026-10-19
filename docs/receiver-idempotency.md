# Webhook 接收端幂等实现

RelayBox 的投递语义是至少一次。相同投递在重试时保留相同的 `X-Webhook-Delivery`；接收端必须持久化去重，并在同一个数据库事务里应用业务修改。只有事务提交后才返回 2xx。RelayBox 无法替任意租户原子提交其数据库或外部副作用，因此不承诺端到端 exactly-once。

## PostgreSQL 表结构

```sql
CREATE TABLE processed_webhook (
    delivery_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    payload_hash BYTEA NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE order_paid_effect (
    event_id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 事务处理顺序

```text
1. 读取原始 HTTP body 和 X-Webhook-Delivery / X-Webhook-Id
2. 校验时间戳和 HMAC-SHA256 签名
3. 开启数据库事务
4. INSERT processed_webhook ... ON CONFLICT DO NOTHING RETURNING delivery_id
5. 若首次插入：在同一事务里修改业务表
6. 若已存在：检查 event_id 和 payload_hash 一致；一致则作为重复请求返回成功，不一致则记录安全告警并返回 409
7. 提交事务
8. 事务提交成功后才向 RelayBox 返回 HTTP 2xx
```

唯一键冲突将并发到达的相同投递串行化；若先到的请求尚未提交，竞争插入会等待它提交或回滚。业务表写入失败时，整个事务回滚，下一次重试仍可执行。若返回 2xx 后接收端崩溃，业务效果和去重记录已经一起提交，重试会命中去重记录。

不要只在 Redis 或进程内存里记录投递 ID，也不要在写去重行之后用另一个独立事务修改业务表。若处理还要调用支付、邮件或其他外部系统，应把相同幂等键传给该系统；如果对方不支持幂等，需要补偿和对账流程，数据库唯一约束不能保护外部副作用。

RelayBox 内置的本地 Chaos Receiver 用 PostgreSQL 唯一键模拟“业务效果”，并在同一个投递 ID 再次出现但事件 ID 或 JSON 内容变化时返回 HTTP 409。它用于验证重试协议，不是通用生产接收器。接收方仍应先验证 RelayBox 的 HMAC 签名，再执行自身业务事务。
