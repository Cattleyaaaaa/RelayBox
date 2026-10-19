import { useState } from "react";
import ChaosPanel from "./ChaosPanel";
import {
  Activity,
  Check,
  CircleAlert,
  Play,
  RotateCcw,
  ShieldCheck,
  Timer,
  Webhook,
} from "lucide-react";
import {
  configureDemoReceiver,
  createEndpoint,
  getAttempts,
  getDeliveries,
  publishEvent,
  replayDelivery,
  type Delivery,
  type DeliveryAttempt,
} from "../lib/api";

type EventRef = { id: string; type: string; createdAt: string };
type Scenario = "retry" | "dead" | "permanent";
type Run = {
  eventId: string;
  eventType: string;
  delivery: Delivery;
  attempts: DeliveryAttempt[];
};

const pause = (milliseconds: number) =>
  new Promise((resolve) => window.setTimeout(resolve, milliseconds));

function explain(error: unknown) {
  const message = error instanceof Error ? error.message : "未知错误";
  const translations: Record<string, string> = {
    "Master API key required":
      "演示接收器需要主密钥。请在连接设置中填写主密钥。",
    "Missing or invalid API key": "租户 API 密钥无效，请重新连接工作空间。",
  };
  if (message.includes("Failed to fetch")) {
    return "无法连接 RelayBox API。请先启动 Docker Compose 后端。";
  }
  return translations[message] ?? message;
}

export default function DemoLab({
  tenantKey,
  masterKey,
  onEvent,
}: {
  tenantKey: string;
  masterKey: string;
  onEvent: (event: EventRef) => void;
}) {
  const [runs, setRuns] = useState<Record<Scenario, Run | null>>({
    retry: null,
    dead: null,
    permanent: null,
  });
  const [busy, setBusy] = useState<Scenario | "replay" | null>(null);
  const [progress, setProgress] = useState<Record<Scenario, string>>({
    retry: "",
    dead: "",
    permanent: "",
  });
  const [error, setError] = useState("");

  const start = async (scenario: Scenario) => {
    setBusy(scenario);
    setError("");
    setRuns((current) => ({ ...current, [scenario]: null }));
    const suffix = crypto.randomUUID();
    const eventType = `demo.${scenario}.${suffix.slice(0, 8)}`;
    const eventIdempotency = `${eventType}-${suffix}`;
    try {
      setProgress((current) => ({ ...current, [scenario]: "准备模拟接收端…" }));
      await configureDemoReceiver(
        masterKey,
        scenario === "retry" ? 2 : 100,
        scenario === "permanent" ? 400 : 500,
      );

      setProgress((current) => ({
        ...current,
        [scenario]: "创建隔离的演示端点…",
      }));
      await createEndpoint(tenantKey, {
        url: "http://app:8080/dev/receiver/deliver",
        eventTypes: [eventType],
        circuitFailureThreshold: scenario === "retry" ? 5 : 100,
        circuitOpenDurationSeconds: 30,
      });

      setProgress((current) => ({
        ...current,
        [scenario]: "发布测试事件，等待 Worker 处理…",
      }));
      const published = await publishEvent(tenantKey, {
        type: eventType,
        idempotencyKey: eventIdempotency,
        payload: {
          orderId: `RB-${suffix.slice(0, 8)}`,
          amount: 12900,
          currency: "CNY",
          demo: true,
        },
      });
      onEvent({
        id: published.eventId,
        type: eventType,
        createdAt: new Date().toISOString(),
      });

      const deadline = Date.now() + 120_000;
      let delivery: Delivery | undefined;
      while (Date.now() < deadline) {
        const deliveries = await getDeliveries(tenantKey, published.eventId);
        delivery = deliveries[0];
        if (delivery && ["SUCCEEDED", "DEAD"].includes(delivery.status)) break;
        await pause(500);
      }
      if (!delivery || !["SUCCEEDED", "DEAD"].includes(delivery.status)) {
        throw new Error("等待投递结果超时。请检查 Worker 是否正在运行。");
      }
      const attempts = await getAttempts(tenantKey, delivery.id);
      setRuns((current) => ({
        ...current,
        [scenario]: {
          eventId: published.eventId,
          eventType,
          delivery,
          attempts,
        },
      }));
      setProgress((current) => ({
        ...current,
        [scenario]:
          scenario === "retry"
            ? "重试流程已完成"
            : scenario === "permanent"
              ? "HTTP 400 已直接进入死信，未进行无效重试"
              : "事件已进入死信，可点击重放",
      }));
    } catch (cause) {
      setError(explain(cause));
      setProgress((current) => ({ ...current, [scenario]: "演示未能完成" }));
    } finally {
      setBusy(null);
    }
  };

  const replay = async () => {
    const run = runs.dead;
    if (!run) return;
    setBusy("replay");
    setError("");
    setProgress((current) => ({ ...current, dead: "恢复接收端并提交重放…" }));
    try {
      await configureDemoReceiver(masterKey, 0);
      await replayDelivery(tenantKey, run.delivery.id);
      const deadline = Date.now() + 120_000;
      let current = run.delivery;
      while (Date.now() < deadline) {
        const deliveries = await getDeliveries(tenantKey, run.eventId);
        current = deliveries[0] ?? current;
        if (current.status === "SUCCEEDED") break;
        await pause(500);
      }
      if (current.status !== "SUCCEEDED") {
        throw new Error("重放等待超时。请检查 Worker 是否正在运行。");
      }
      const attempts = await getAttempts(tenantKey, current.id);
      setRuns((currentRuns) => ({
        ...currentRuns,
        dead: { ...run, delivery: current, attempts },
      }));
      setProgress((currentProgress) => ({
        ...currentProgress,
        dead: "重放成功，端点已恢复投递",
      }));
    } catch (cause) {
      setError(explain(cause));
      setProgress((current) => ({ ...current, dead: "重放失败" }));
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="subpage demo-lab">
      <div className="subpage-heading">
        <div>
          <div className="eyebrow">
            <i /> 交互式业务演示
          </div>
          <h1>可靠投递实验室</h1>
          <p>
            一键触发真实 API、PostgreSQL 队列和
            Worker，现场观察失败、重试、死信与人工恢复。
          </p>
        </div>
        <div className="demo-live">
          <i /> 使用当前租户与真实 Worker
        </div>
      </div>

      {!masterKey && (
        <div className="demo-notice">
          <CircleAlert size={17} />
          当前工作空间没有主密钥。打开左侧“连接设置”，填写主密钥后即可运行接收器演示。
        </div>
      )}
      {error && (
        <div className="demo-error">
          <CircleAlert size={17} />
          {error}
        </div>
      )}

      <div className="demo-scenarios">
        <ScenarioCard
          number="01"
          title="自动重试后成功"
          description="模拟接收端前两次返回 HTTP 500，第三次返回 200。"
          icon={<RotateCcw size={18} />}
          button={busy === "retry" ? "正在投递…" : "运行重试演示"}
          disabled={Boolean(busy) || !tenantKey || !masterKey}
          progress={progress.retry}
          run={runs.retry}
          onRun={() => void start("retry")}
        />
        <ScenarioCard
          number="02"
          title="死信与人工重放"
          description="先让接收端持续失败直到进入死信，再恢复接收端并手动重放。"
          icon={<ShieldCheck size={18} />}
          button={busy === "dead" ? "等待进入死信…" : "运行死信演示"}
          disabled={Boolean(busy) || !tenantKey || !masterKey}
          progress={progress.dead}
          run={runs.dead}
          action={
            runs.dead?.delivery.status === "DEAD" ? (
              <button
                className="button primary"
                disabled={Boolean(busy)}
                onClick={replay}
              >
                <Play size={15} />
                {busy === "replay" ? "重放中…" : "恢复接收端并重放"}
              </button>
            ) : undefined
          }
          onRun={() => void start("dead")}
        />
        <ScenarioCard
          number="03"
          title="永久失败快速终止"
          description="模拟接收端返回 HTTP 400。业务请求无效，首次失败后直接进入死信，不再占用重试队列。"
          icon={<CircleAlert size={18} />}
          button={busy === "permanent" ? "正在处理…" : "运行永久失败演示"}
          disabled={Boolean(busy) || !tenantKey || !masterKey}
          progress={progress.permanent}
          run={runs.permanent}
          onRun={() => void start("permanent")}
        />
      </div>

      <ChaosPanel masterKey={masterKey} />

      <div className="panel demo-explainer">
        <div className="demo-explainer-icon">
          <Activity size={18} />
        </div>
        <div>
          <div className="section-kicker">后台真实流程</div>
          <b>
            事务写入 → SKIP LOCKED 领取 → HMAC 签名发送 → 尝试记录与退避重试
          </b>
          <p>
            每次演示都会创建带唯一事件类型和幂等键的数据，不会复用已有演示记录。
          </p>
        </div>
      </div>
    </section>
  );
}

function ScenarioCard({
  number,
  title,
  description,
  icon,
  button,
  disabled,
  progress,
  run,
  action,
  onRun,
}: {
  number: string;
  title: string;
  description: string;
  icon: React.ReactNode;
  button: string;
  disabled: boolean;
  progress: string;
  run: Run | null;
  action?: React.ReactNode;
  onRun: () => void;
}) {
  return (
    <article className="panel demo-card">
      <div className="demo-card-head">
        <div className="demo-icon">{icon}</div>
        <span>演示 {number}</span>
        {run && (
          <b className={`demo-result ${run.delivery.status.toLowerCase()}`}>
            {run.delivery.status === "SUCCEEDED"
              ? "已成功"
              : run.delivery.status === "DEAD"
                ? "已进入死信"
                : run.delivery.status}
          </b>
        )}
      </div>
      <h2>{title}</h2>
      <p>{description}</p>
      <div className="demo-card-actions">
        <button className="button primary" onClick={onRun} disabled={disabled}>
          <Play size={15} />
          {button}
        </button>
        {action}
      </div>
      {progress && (
        <div className="demo-progress">
          <Timer size={14} />
          {progress}
        </div>
      )}
      {run && (
        <div className="demo-attempts">
          {run.attempts.map((attempt) => (
            <div className="demo-attempt" key={attempt.id}>
              <span
                className={
                  attempt.status === "SUCCEEDED" ? "attempt-ok" : "attempt-fail"
                }
              >
                {attempt.status === "SUCCEEDED" ? (
                  <Check size={14} />
                ) : (
                  <Webhook size={14} />
                )}
              </span>
              <b>第 {attempt.attemptNumber} 次</b>
              <code>HTTP {attempt.responseCode ?? "—"}</code>
              <small>{attempt.durationMs ?? "—"} ms</small>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}
