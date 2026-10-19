import { useEffect, useState } from "react";
import { Activity, Check, Copy, RefreshCw, ServerCrash } from "lucide-react";
import { getChaosReport, type ChaosReport } from "../lib/api";

const command = ".\\scripts\\chaos-recovery.ps1";

export default function ChaosPanel({ masterKey }: { masterKey: string }) {
  const [result, setResult] = useState<{ key: string; report: ChaosReport } | null>(null);
  const [requestError, setRequestError] = useState<{ key: string; message: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const report = result?.key === masterKey ? result.report : null;
  const error = requestError?.key === masterKey ? requestError.message : "";

  useEffect(() => {
    if (!masterKey) return;
    let active = true;
    const refresh = async () => {
      try {
        const next = await getChaosReport(masterKey);
        if (active) {
          setResult({ key: masterKey, report: next });
          setRequestError(null);
        }
      } catch (cause) {
        if (active) setRequestError({ key: masterKey, message: cause instanceof Error ? cause.message : "读取演示状态失败" });
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 1500);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [masterKey]);

  const first = report?.attempts.find((item) => item.attemptNumber === 1);
  const second = report?.attempts.find((item) => item.attemptNumber === 2);
  const receipts = report?.receiver.receipts ?? [];
  const recovered =
    report?.deliveryStatus === "SUCCEEDED" &&
    first?.status === "FAILED" &&
    first.error?.includes("worker lease expired") &&
    second?.status === "SUCCEEDED" &&
    receipts.length === 2 &&
    report.receiver.appliedBusinessEffects === 1 &&
    receipts[0].deliveryId === receipts[1].deliveryId;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      setCopied(false);
    }
  };

  return (
    <section className="panel chaos-panel" aria-label="Worker 故障接管演示">
      <div className="chaos-heading">
        <div className="chaos-heading-icon"><ServerCrash size={23} /></div>
        <div>
          <div className="section-kicker">故障注入 · 真实数据库状态</div>
          <h2>Worker 崩溃后，事件去哪了？</h2>
          <p>接收端已经收到请求，Worker 却在收到响应前退出。观察租约恢复、再次投递和接收端去重。</p>
        </div>
        <span className={`chaos-badge ${recovered ? "verified" : ""}`}>
          {recovered ? <Check size={14} /> : <Activity size={14} />}
          {recovered ? "接管已验证" : report?.deliveryId ? "观察中" : "等待演示"}
        </span>
      </div>

      <div className="chaos-command">
        <div>
          <small>在 IDEA 终端进入 RelayBox 的 backend 目录后运行</small>
          <code>{command}</code>
        </div>
        <button type="button" onClick={() => void copy()} aria-label="复制故障演示命令">
          {copied ? <Check size={16} /> : <Copy size={16} />}
          {copied ? "已复制" : "复制"}
        </button>
      </div>

      <div className="chaos-flow">
        <FlowStep number="01" title="请求到达" detail="第一次 HTTP 请求进入接收端" done={receipts.length >= 1} />
        <FlowStep number="02" title="租约恢复" detail="旧尝试因 Worker 退出而过期" done={Boolean(first?.error?.includes("worker lease expired"))} />
        <FlowStep number="03" title="再次投递" detail="另一个 Worker 完成第二次尝试" done={second?.status === "SUCCEEDED"} />
        <FlowStep number="04" title="接收端去重" detail="两次请求只应用一次业务效果" done={Boolean(recovered)} />
      </div>

      <div className="chaos-evidence">
        <div className="chaos-count"><b>{report?.receiver.receivedCount ?? 0}</b><span>实际 HTTP 请求</span></div>
        <div className="chaos-count"><b>{report?.attempts.length ?? 0}</b><span>持久化尝试记录</span></div>
        <div className="chaos-count good"><b>{report?.receiver.appliedBusinessEffects ?? 0}</b><span>演示业务效果</span></div>
      </div>

      {report?.deliveryId && (
        <div className="chaos-detail">
          <div className="chaos-detail-title"><RefreshCw size={14} /> 同一投递 ID：<code>{report.deliveryId}</code></div>
          {report.attempts.map((attempt) => (
            <div className="chaos-detail-row" key={attempt.attemptNumber}>
              <span>尝试 {attempt.attemptNumber}</span>
              <b>{attempt.status === "FAILED" ? "租约过期" : attempt.status === "SUCCEEDED" ? "投递成功" : "执行中"}</b>
              <small>{attempt.error ?? (attempt.responseCode ? `HTTP ${attempt.responseCode}` : "等待结果")}</small>
            </div>
          ))}
          {receipts.map((receipt) => (
            <div className="chaos-detail-row" key={receipt.requestNumber}>
              <span>接收 {receipt.requestNumber}</span>
              <b>{receipt.idempotencyConflict ? "幂等键冲突" : receipt.businessEffectApplied ? "应用业务效果" : "幂等去重"}</b>
              <small>HTTP {receipt.responseCode} · {new Date(receipt.receivedAt).toLocaleTimeString("zh-CN", { hour12: false })}</small>
            </div>
          ))}
        </div>
      )}
      {error && <p className="chaos-error">读取状态失败：{error}</p>}
      <p className="chaos-caveat">演示接收端用 PostgreSQL 唯一键持久化去重；真实业务应将投递 ID 唯一约束和业务写入放进同一数据库事务。</p>
    </section>
  );
}

function FlowStep({ number, title, detail, done }: { number: string; title: string; detail: string; done: boolean }) {
  return (
    <div className={`chaos-step ${done ? "done" : ""}`}>
      <span>{done ? <Check size={15} /> : number}</span>
      <b>{title}</b>
      <small>{detail}</small>
    </div>
  );
}
