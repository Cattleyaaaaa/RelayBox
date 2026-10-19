import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import {
  Activity,
  ArrowDownLeft,
  ArrowRight,
  ArrowUpRight,
  Bell,
  Check,
  ChevronDown,
  Clock3,
  Code2,
  FlaskConical,
  Gauge,
  Layers3,
  LockKeyhole,
  LogOut,
  MoreHorizontal,
  Plus,
  Radio,
  RefreshCw,
  RotateCcw,
  Send,
  Settings2,
  ShieldCheck,
  Sparkles,
  Webhook,
  X,
  Zap,
} from "lucide-react";
import AnimatedContent from "./components/AnimatedContent";
import DemoLab from "./components/DemoLab";
import {
  createEndpoint,
  createTenant,
  getAttempts,
  getDeliveries,
  getEndpoints,
  getHealth,
  getQueueDepth,
  publishEvent,
  replayDelivery,
  setEndpointEnabled,
  type Delivery,
  type Endpoint,
  type QueueDepth,
  type Tenant,
} from "./lib/api";
import "./App.css";
import "./light-theme.css";

type EventRef = { id: string; type: string; createdAt: string };
type Attempt = {
  id: string;
  attemptNumber: number;
  status: string;
  responseCode: number | null;
  error: string | null;
  durationMs: number | null;
};
type Toast = { tone: "success" | "error"; text: string };
const emptyQueue: QueueDepth = { pending: 0, inFlight: 0, dead: 0 };
const loadSession = <T,>(key: string, fallback: T): T => {
  try {
    return JSON.parse(sessionStorage.getItem(key) ?? "") as T;
  } catch {
    return fallback;
  }
};
const format = (value: number) => new Intl.NumberFormat("zh-CN").format(value);
const short = (value: string) => `${value.slice(0, 8)}…${value.slice(-4)}`;
const ago = (date?: string) => {
  if (!date) return "刚刚";
  const seconds = Math.max(
    0,
    Math.floor((Date.now() - new Date(date).getTime()) / 1000),
  );
  return seconds < 60
    ? `${seconds} 秒前`
    : seconds < 3600
      ? `${Math.floor(seconds / 60)} 分钟前`
      : `${Math.floor(seconds / 3600)} 小时前`;
};
const statusLabel = (status: string) =>
  ({
    PENDING: "待投递",
    IN_FLIGHT: "投递中",
    SUCCEEDED: "已成功",
    FAILED: "失败",
    DEAD: "死信",
  })[status] ?? status.replaceAll("_", " ");
const circuitLabel = (state: string) =>
  ({ CLOSED: "正常", OPEN: "熔断中", HALF_OPEN: "半开探测" })[state] ?? state;
const errorLabel = (message: string) => {
  const translations: Record<string, string> = {
    "Missing or invalid API key": "API 密钥缺失或无效",
    "Master API key required": "请提供主密钥",
    "Tenant name is required": "请填写租户名称",
    "Payload must be valid JSON": "事件内容必须是有效的 JSON",
    "Tenant event rate limit exceeded": "已达到租户事件速率上限，请稍后重试",
    "Delivery does not exist": "投递记录不存在",
    "Endpoint does not exist": "接收端点不存在",
    "idempotency key is already associated with a different event":
      "该幂等键已绑定其他事件类型或内容，请换用新幂等键",
  };
  return translations[message] ?? message;
};

export default function App() {
  const [tenant, setTenant] = useState<Tenant | null>(() =>
    loadSession("relaybox.tenant", null),
  );
  const [key, setKey] = useState(
    () => sessionStorage.getItem("relaybox.apiKey") ?? "",
  );
  const [adminKey, setAdminKey] = useState(
    () => sessionStorage.getItem("relaybox.masterKey") ?? "",
  );
  const [refs, setRefs] = useState<EventRef[]>(() =>
    loadSession("relaybox.events", []),
  );
  const [view, setView] = useState<
    "overview" | "endpoints" | "events" | "demo"
  >("overview");
  const [endpoints, setEndpoints] = useState<Endpoint[]>([]);
  const [deliveries, setDeliveries] = useState<Delivery[]>([]);
  const [queue, setQueue] = useState(emptyQueue);
  const [online, setOnline] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [updated, setUpdated] = useState<Date | null>(null);
  const [toast, setToast] = useState<Toast | null>(null);
  const [connectOpen, setConnectOpen] = useState(
    () => !sessionStorage.getItem("relaybox.apiKey"),
  );
  const [endpointOpen, setEndpointOpen] = useState(false);
  const [eventOpen, setEventOpen] = useState(false);
  const [attempts, setAttempts] = useState<Attempt[] | null>(null);

  const refresh = useCallback(
    async (quiet = false) => {
      if (!quiet) setRefreshing(true);
      const health = await getHealth();
      setOnline(health);
      const [depth, routes, traces] = await Promise.all([
        getQueueDepth(),
        key ? getEndpoints(key).catch(() => []) : Promise.resolve([]),
        key
          ? Promise.all(
              refs.slice(0, 8).map(async (event) => ({
                event,
                items: await getDeliveries(key, event.id).catch(() => []),
              })),
            )
          : Promise.resolve([]),
      ]);
      setQueue(depth);
      setEndpoints(routes);
      setDeliveries(
        traces
          .flatMap(({ event, items }) =>
            items.map((item) => ({
              ...item,
              eventType: event.type,
              eventCreatedAt: event.createdAt,
            })),
          )
          .sort(
            (a, b) =>
              new Date(b.eventCreatedAt ?? "").getTime() -
              new Date(a.eventCreatedAt ?? "").getTime(),
          ),
      );
      setUpdated(new Date());
      if (!quiet) setRefreshing(false);
    },
    [key, refs],
  );

  useEffect(() => {
    void refresh(true);
    const timer = window.setInterval(() => void refresh(true), 7000);
    return () => window.clearInterval(timer);
  }, [refresh]);
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 4000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const rememberEvent = (event: EventRef) =>
    setRefs((current) => {
      const next = [
        event,
        ...current.filter((item) => item.id !== event.id),
      ].slice(0, 12);
      sessionStorage.setItem("relaybox.events", JSON.stringify(next));
      return next;
    });
  const connect = (
    nextTenant: Tenant,
    nextKey: string,
    nextAdminKey: string,
  ) => {
    sessionStorage.setItem("relaybox.tenant", JSON.stringify(nextTenant));
    sessionStorage.setItem("relaybox.apiKey", nextKey);
    sessionStorage.setItem("relaybox.masterKey", nextAdminKey);
    setTenant(nextTenant);
    setKey(nextKey);
    setAdminKey(nextAdminKey);
    setConnectOpen(false);
    setToast({ tone: "success", text: `已连接到「${nextTenant.name}」` });
  };
  const connectSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const existingKey = String(data.get("tenantKey") ?? "").trim();
    const rootKey = String(data.get("masterKey") ?? "").trim();
    const name = String(data.get("name") ?? "").trim();
    if (existingKey) {
      connect(
        tenant ?? { id: "", name: name || "租户工作空间" },
        existingKey,
        rootKey,
      );
      return;
    }
    void createTenant(rootKey, name)
      .then((created) =>
        connect(
          { id: created.id, name: created.name },
          created.apiKey,
          rootKey,
        ),
      )
      .catch((error: Error) =>
        setToast({ tone: "error", text: errorLabel(error.message) }),
      );
  };
  const endpointSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    try {
      await createEndpoint(key, {
        url: String(data.get("url")),
        eventTypes: String(data.get("types"))
          .split(",")
          .map((part) => part.trim())
          .filter(Boolean),
        circuitFailureThreshold: Number(data.get("threshold")),
        circuitOpenDurationSeconds: Number(data.get("cooldown")),
      });
      setEndpointOpen(false);
      setToast({
        tone: "success",
        text: "接收端点已创建，顺序投递与熔断保护已启用",
      });
      await refresh(true);
    } catch (error) {
      setToast({ tone: "error", text: errorLabel((error as Error).message) });
    }
  };
  const eventSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    try {
      const type = String(data.get("type")).trim();
      const result = await publishEvent(key, {
        type,
        idempotencyKey: String(data.get("idempotency") || crypto.randomUUID()),
        payload: JSON.parse(String(data.get("payload"))),
      });
      rememberEvent({
        id: result.eventId,
        type,
        createdAt: new Date().toISOString(),
      });
      setEventOpen(false);
      setView("events");
      setToast({
        tone: "success",
        text: result.duplicate
          ? "重复事件已安全识别，没有重复投递"
          : `事件已进入队列，将投递到 ${result.deliveryCount} 个端点`,
      });
    } catch (error) {
      setToast({
        tone: "error",
        text: errorLabel(
          (error as Error).message || "Payload must be valid JSON",
        ),
      });
    }
  };
  const replay = async (delivery: Delivery) => {
    try {
      await replayDelivery(key, delivery.id);
      setToast({ tone: "success", text: "重放请求已排入端点队列末尾" });
      await refresh(true);
    } catch (error) {
      setToast({ tone: "error", text: errorLabel((error as Error).message) });
    }
  };
  const toggleEndpoint = async (endpoint: Endpoint) => {
    try {
      await setEndpointEnabled(key, endpoint.id, !endpoint.enabled);
      await refresh(true);
    } catch (error) {
      setToast({ tone: "error", text: errorLabel((error as Error).message) });
    }
  };
  const openAttempts = async (delivery: Delivery) => {
    try {
      setAttempts(await getAttempts(key, delivery.id));
    } catch (error) {
      setToast({ tone: "error", text: errorLabel((error as Error).message) });
    }
  };
  const successRate = useMemo(() => {
    const done = deliveries.filter(
      (item) => item.status === "SUCCEEDED" || item.status === "DEAD",
    );
    return done.length
      ? Math.round(
          (done.filter((item) => item.status === "SUCCEEDED").length /
            done.length) *
            100,
        )
      : 100;
  }, [deliveries]);
  const nav = [
    { id: "overview" as const, name: "总览", icon: Layers3 },
    { id: "endpoints" as const, name: "接收端点", icon: Webhook },
    { id: "events" as const, name: "事件流", icon: Radio },
    { id: "demo" as const, name: "演示中心", icon: FlaskConical },
  ];

  return (
    <div className="app-shell theme-light">
      <div className="ambient ambient-one" />
      <div className="ambient ambient-two" />
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">
            <Webhook size={19} />
          </div>
          <div>
            <b>RelayBox</b>
            <span>消息投递控制台</span>
          </div>
        </div>
        <button
          className="workspace-picker"
          onClick={() => setConnectOpen(true)}
        >
          <span className="workspace-avatar">
            {(tenant?.name || "R")[0].toUpperCase()}
          </span>
          <span className="workspace-name">
            <b>{tenant?.name || "连接租户"}</b>
            <small>工作空间</small>
          </span>
          <ChevronDown size={15} />
        </button>
        <div className="nav-caption">工作空间</div>
        <nav>
          {nav.map(({ id, name, icon: Icon }) => (
            <button
              key={id}
              className={`nav-item ${view === id ? "active" : ""}`}
              onClick={() => setView(id)}
            >
              <Icon size={17} />
              <span>{name}</span>
              {id === "events" && deliveries.length > 0 && (
                <i>{deliveries.length}</i>
              )}
            </button>
          ))}
        </nav>
        <div className="nav-caption system-caption">系统</div>
        <button className="nav-item" onClick={() => setConnectOpen(true)}>
          <Settings2 size={17} />
          <span>连接设置</span>
        </button>
        <div className="sidebar-spacer" />
        <div className="worker-card">
          <div className="worker-card-top">
            <span>工作进程池</span>
            <span className="live-tag">
              <i />
              运行中
            </span>
          </div>
          <div className="worker-value">
            02 <small>个实例</small>
          </div>
          <div className="worker-bars">
            {Array.from({ length: 12 }, (_, i) => (
              <i key={i} />
            ))}
          </div>
          <div className="worker-card-foot">
            <span>并发上限</span>
            <b>16 / 32</b>
          </div>
        </div>
        <div className="profile">
          <div className="profile-avatar">RB</div>
          <div className="profile-name">
            <b>{tenant?.name || "RelayBox 演示空间"}</b>
            <small>租户工作空间</small>
          </div>
          <button
            title="断开连接"
            onClick={() => {
              sessionStorage.removeItem("relaybox.apiKey");
              sessionStorage.removeItem("relaybox.tenant");
              setKey("");
              setTenant(null);
              setConnectOpen(true);
            }}
          >
            <LogOut size={16} />
          </button>
        </div>
      </aside>

      <main className="main-shell">
        <header className="topbar">
          <div className="breadcrumbs">
            <span>RelayBox</span>
            <b>/</b>
            <strong>{nav.find((item) => item.id === view)?.name}</strong>
          </div>
          <div className="topbar-actions">
            <span className={`system-status ${online ? "online" : ""}`}>
              <i />
              {online ? "系统运行正常" : "API 暂不可用"}
            </span>
            <button
              className="icon-btn"
              onClick={() => void refresh()}
              title="刷新数据"
            >
              <RefreshCw size={16} className={refreshing ? "spin" : ""} />
            </button>
            <button className="icon-btn bell-btn" title="通知">
              <Bell size={16} />
              <i />
            </button>
            <div className="user-avatar">
              {(tenant?.name || "R")[0].toUpperCase()}
            </div>
          </div>
        </header>
        <div className="page-content">
          {view === "overview" && (
            <>
              <section className="hero">
                <div className="hero-copy">
                  <div className="eyebrow">
                    <i /> Webhook 投递基础设施 <span>平台 01</span>
                  </div>
                  <h1 className="hero-title">让事件投递，尽在掌控。</h1>
                  <p>
                    提供可靠的事件投递能力，内置顺序保障、失败重试与熔断保护。
                  </p>
                  <div className="hero-actions">
                    <button
                      className="button primary"
                      onClick={() => setEventOpen(true)}
                    >
                      <Send size={15} />
                      发送事件
                      <ArrowRight size={15} />
                    </button>
                    <button
                      className="button subtle"
                      onClick={() => setView("endpoints")}
                    >
                      管理接收端点
                      <ArrowUpRight size={15} />
                    </button>
                  </div>
                </div>
                <div className="hero-visual">
                  <div className="orbit orbit-a" />
                  <div className="orbit orbit-b" />
                  <div className="orbit orbit-c" />
                  <div className="orbit-center">
                    <div />
                    <Webhook size={36} />
                  </div>
                  <span className="orbit-node node-a">
                    <Radio size={14} />
                  </span>
                  <span className="orbit-node node-b">
                    <Check size={14} />
                  </span>
                  <span className="orbit-node node-c">
                    <Zap size={13} />
                  </span>
                  <span className="orbit-label label-a">接入</span>
                  <span className="orbit-label label-b">投递</span>
                  <span className="orbit-signature">签名 · 重试 · 验证</span>
                </div>
              </section>
              <section className="metrics-grid">
                <Metric
                  title="待投递事件"
                  value={queue.pending}
                  subtitle="等待工作进程处理"
                  foot={`${endpoints.length} 个启用端点`}
                  icon={Clock3}
                  tone="purple"
                />
                <Metric
                  title="正在投递"
                  value={queue.inFlight}
                  subtitle="已由工作进程领取"
                  foot="2 个工作进程实例"
                  icon={Activity}
                  tone="blue"
                />
                <Metric
                  title="死信事件"
                  value={queue.dead}
                  subtitle="可查看详情或重新投递"
                  foot={queue.dead ? "需要处理" : "队列为空"}
                  icon={ShieldCheck}
                  tone="rose"
                />
                <Metric
                  title="投递成功率"
                  value={`${successRate}%`}
                  subtitle="近期已完成投递结果"
                  foot="最近 12 条事件记录"
                  icon={Gauge}
                  tone="green"
                />
              </section>
              <section className="dashboard-grid">
                <div className="panel recent-panel">
                  <PanelHead
                    kicker="实时运行"
                    title="最近投递记录"
                    action="查看事件流"
                    onAction={() => setView("events")}
                  />
                  <DeliveryTable
                    items={deliveries.slice(0, 6)}
                    onReplay={replay}
                    onAttempts={openAttempts}
                  />
                </div>
                <div className="panel endpoint-mini-panel">
                  <PanelHead
                    kicker="投递目标"
                    title="端点状态"
                    action="＋"
                    onAction={() => setEndpointOpen(true)}
                  />
                  <div className="endpoint-mini-list">
                    {endpoints.slice(0, 4).map((endpoint) => (
                      <EndpointMini key={endpoint.id} endpoint={endpoint} />
                    ))}
                    {endpoints.length === 0 && (
                      <Empty
                        title="还没有接收端点"
                        body="添加一个目标地址来接收事件。"
                        action="创建接收端点"
                        onAction={() => setEndpointOpen(true)}
                      />
                    )}
                  </div>
                  <button
                    className="panel-link"
                    onClick={() => setView("endpoints")}
                  >
                    端点管理 <ArrowRight size={14} />
                  </button>
                </div>
              </section>
              <section className="bottom-grid">
                <div className="panel pipeline-panel">
                  <PanelHead
                    kicker="投递流水线"
                    title="事件处理流程"
                    note="由 PostgreSQL 提供支持"
                  />
                  <div className="pipeline">
                    <PipeStep
                      number="01"
                      title="接入"
                      note="幂等 API"
                      icon={ArrowDownLeft}
                      state="done"
                    />
                    <div className="pipe-line">
                      <i />
                    </div>
                    <PipeStep
                      number="02"
                      title="持久化"
                      note="事务型发件箱"
                      icon={Layers3}
                      state="done"
                    />
                    <div className="pipe-line moving">
                      <i />
                    </div>
                    <PipeStep
                      number="03"
                      title="调度"
                      note="SKIP LOCKED 工作进程"
                      icon={Zap}
                      state="active"
                    />
                    <div className="pipe-line">
                      <i />
                    </div>
                    <PipeStep
                      number="04"
                      title="投递"
                      note="签名 Webhook"
                      icon={Webhook}
                      state="idle"
                    />
                  </div>
                </div>
                <div className="panel guard-panel">
                  <div className="section-kicker">可靠性保护</div>
                  <div className="guard-title">
                    <ShieldCheck size={18} />
                    <h2>防护机制已启用</h2>
                  </div>
                  <Guard label="按租户限流" icon={Check} tone="green" />
                  <Guard label="端点熔断保护" icon={Zap} tone="purple" />
                  <Guard label="HMAC 请求签名" icon={LockKeyhole} tone="blue" />
                </div>
              </section>
              <div className="refresh-note">
                <span>
                  <i /> 每 7 秒自动刷新
                </span>
                <span>
                  {updated
                    ? `更新于 ${updated.toLocaleTimeString("zh-CN")}`
                    : "等待首次同步"}
                </span>
              </div>
            </>
          )}
          {view === "endpoints" && (
            <section className="subpage">
              <div className="subpage-heading">
                <div>
                  <div className="eyebrow">
                    <i /> 端点管理
                  </div>
                  <h1>接收端点</h1>
                  <p>管理投递目标、投递策略与熔断状态。</p>
                </div>
                <button
                  className="button primary"
                  onClick={() => setEndpointOpen(true)}
                >
                  <Plus size={16} />
                  添加端点
                </button>
              </div>
              <div className="endpoint-card-grid">
                {endpoints.map((endpoint, index) => (
                  <AnimatedContent
                    key={endpoint.id}
                    trigger="mount"
                    delay={index * 0.06}
                    distance={18}
                    duration={0.5}
                  >
                    <EndpointCard
                      endpoint={endpoint}
                      onToggle={() => void toggleEndpoint(endpoint)}
                    />
                  </AnimatedContent>
                ))}
                {endpoints.length === 0 && (
                  <div className="panel empty-panel">
                    <Empty
                      title="从添加第一个端点开始"
                      body="添加投递目标后，RelayBox 将负责签名投递、顺序保障、失败重试与恢复。"
                      action="创建第一个端点"
                      onAction={() => setEndpointOpen(true)}
                    />
                  </div>
                )}
              </div>
            </section>
          )}
          {view === "events" && (
            <section className="subpage">
              <div className="subpage-heading">
                <div>
                  <div className="eyebrow">
                    <i /> 事件管理
                  </div>
                  <h1>事件流</h1>
                  <p>
                    查看近期发布记录、投递顺序、重试过程，并重新投递失败事件。
                  </p>
                </div>
                <button
                  className="button primary"
                  onClick={() => setEventOpen(true)}
                >
                  <Send size={15} />
                  发布事件
                </button>
              </div>
              <div className="panel stream-panel">
                <DeliveryTable
                  items={deliveries}
                  onReplay={replay}
                  onAttempts={openAttempts}
                  expanded
                />
              </div>
              {refs.length === 0 && (
                <div className="stream-empty">
                  <div className="empty-orbit">
                    <Radio size={19} />
                  </div>
                  <h3>当前会话还没有事件</h3>
                  <p>发布一个事件即可查看实时投递记录。</p>
                </div>
              )}
            </section>
          )}
          {view === "demo" && (
            <DemoLab
              tenantKey={key}
              masterKey={adminKey}
              onEvent={rememberEvent}
            />
          )}
        </div>
      </main>

      {connectOpen && (
        <Modal
          title="连接租户"
          eyebrow="工作空间设置"
          onClose={() => setConnectOpen(false)}
        >
          <form className="form-stack" onSubmit={connectSubmit}>
            <p className="modal-lead">
              使用已有租户密钥连接，或使用 RelayBox 主密钥创建演示工作空间。
            </p>
            <label>
              租户 API 密钥
              <input
                name="tenantKey"
                autoComplete="off"
                placeholder="粘贴已有的租户密钥"
              />
            </label>
            <div className="form-divider">或创建新的工作空间</div>
            <label>
              主密钥
              <input
                name="masterKey"
                type="password"
                placeholder="local-master-key"
                defaultValue={adminKey}
              />
            </label>
            <label>
              租户名称
              <input name="name" placeholder="例如：示例商城" />
            </label>
            <div className="security-note">
              <LockKeyhole size={14} />
              密钥仅保存在当前浏览器会话中，并且只会发送到你的 RelayBox API。
            </div>
            <button className="button primary submit-button">
              连接工作空间
              <ArrowRight size={15} />
            </button>
          </form>
        </Modal>
      )}
      {endpointOpen && (
        <Modal
          title="注册接收端点"
          eyebrow="新增投递目标"
          onClose={() => setEndpointOpen(false)}
        >
          <form
            className="form-stack"
            onSubmit={(event) => void endpointSubmit(event)}
          >
            <p className="modal-lead">
              每个投递目标都有独立的顺序队列与熔断器。
            </p>
            <label>
              目标 URL
              <input
                name="url"
                required
                type="url"
                placeholder="https://api.example.com/webhooks"
              />
            </label>
            <label>
              事件类型 <small>多个类型用逗号分隔</small>
              <input name="types" required defaultValue="order.paid" />
            </label>
            <div className="form-pair">
              <label>
                熔断失败阈值
                <input
                  name="threshold"
                  type="number"
                  min="1"
                  max="1000"
                  defaultValue="5"
                />
              </label>
              <label>
                熔断冷却时间（秒）
                <input
                  name="cooldown"
                  type="number"
                  min="1"
                  max="3600"
                  defaultValue="30"
                />
              </label>
            </div>
            <div className="security-note">
              <ShieldCheck size={14} />
              系统将自动生成独立的 HMAC-SHA256 签名密钥。
            </div>
            <button className="button primary submit-button">
              注册投递目标
              <ArrowRight size={15} />
            </button>
          </form>
        </Modal>
      )}
      {eventOpen && (
        <Modal
          title="发布事件"
          eyebrow="事件调试台"
          onClose={() => setEventOpen(false)}
          wide
        >
          <form
            className="form-stack"
            onSubmit={(event) => void eventSubmit(event)}
          >
            <p className="modal-lead">
              事件数据与发往各个端点的投递任务会在同一个数据库事务中保存。
            </p>
            <label>
              事件类型
              <input name="type" required defaultValue="order.paid" />
            </label>
            <label>
              幂等键
              <div className="input-action">
                <input name="idempotency" placeholder="留空时自动生成" />
                <button
                  type="button"
                  title="生成幂等键"
                  onClick={(event) => {
                    (
                      event.currentTarget
                        .previousElementSibling as HTMLInputElement
                    ).value = crypto.randomUUID();
                  }}
                >
                  <Sparkles size={15} />
                </button>
              </div>
            </label>
            <label>
              JSON 事件内容
              <textarea
                name="payload"
                required
                rows={7}
                defaultValue={
                  '{\n  "orderId": "ORD-2048",\n  "amount": 12900,\n  "currency": "USD"\n}'
                }
              />
            </label>
            <button className="button primary submit-button">
              加入投递队列
              <Send size={15} />
            </button>
          </form>
        </Modal>
      )}
      {attempts && (
        <Modal
          title="投递尝试记录"
          eyebrow="投递追踪"
          onClose={() => setAttempts(null)}
        >
          <div className="attempt-list">
            {attempts.length === 0 ? (
              <p className="empty-copy">还没有投递尝试记录。</p>
            ) : (
              attempts.map((attempt) => (
                <div className="attempt-row" key={attempt.id}>
                  <span
                    className={`attempt-state ${attempt.status.toLowerCase()}`}
                  >
                    {attempt.status === "SUCCEEDED" ? (
                      <Check size={14} />
                    ) : (
                      <RotateCcw size={14} />
                    )}
                  </span>
                  <div>
                    <b>第 {attempt.attemptNumber} 次尝试</b>
                    <small>
                      {attempt.error ||
                        (attempt.responseCode
                          ? `HTTP ${attempt.responseCode}`
                          : statusLabel(attempt.status))}
                    </small>
                  </div>
                  <span className="attempt-time">
                    {attempt.durationMs ?? "—"} ms
                  </span>
                </div>
              ))
            )}
          </div>
        </Modal>
      )}
      {toast && (
        <div className={`toast ${toast.tone}`}>
          <span>
            {toast.tone === "success" ? <Check size={15} /> : <X size={15} />}
          </span>
          {toast.text}
          <button onClick={() => setToast(null)}>
            <X size={14} />
          </button>
        </div>
      )}
    </div>
  );
}

function Metric({
  title,
  value,
  subtitle,
  foot,
  icon: Icon,
  tone,
}: {
  title: string;
  value: number | string;
  subtitle: string;
  foot: string;
  icon: typeof Activity;
  tone: string;
}) {
  return (
    <AnimatedContent trigger="mount" distance={16} duration={0.55}>
      <div className={`metric-card ${tone}`}>
        <div className="metric-head">
          <span>{title}</span>
          <i>
            <Icon size={16} />
          </i>
        </div>
        <strong>{typeof value === "number" ? format(value) : value}</strong>
        <small>{subtitle}</small>
        <div className="metric-foot">
          <i />
          {foot}
        </div>
      </div>
    </AnimatedContent>
  );
}

function PanelHead({
  kicker,
  title,
  action,
  onAction,
  note,
}: {
  kicker: string;
  title: string;
  action?: string;
  onAction?: () => void;
  note?: string;
}) {
  return (
    <div className="panel-head">
      <div>
        <div className="section-kicker">{kicker}</div>
        <h2>{title}</h2>
      </div>
      {action ? (
        <button className="panel-action" onClick={onAction}>
          {action === "＋" ? (
            <Plus size={16} />
          ) : (
            <>
              {action}
              <ArrowRight size={14} />
            </>
          )}
        </button>
      ) : (
        note && <span className="muted-note">{note}</span>
      )}
    </div>
  );
}

function DeliveryTable({
  items,
  onReplay,
  onAttempts,
  expanded = false,
}: {
  items: Delivery[];
  onReplay: (item: Delivery) => void;
  onAttempts: (item: Delivery) => void;
  expanded?: boolean;
}) {
  if (!items.length)
    return (
      <div className="table-empty">
        <div className="empty-orbit">
          <Webhook size={18} />
        </div>
        <b>等待投递记录</b>
        <span>发布事件后，投递追踪会显示在这里。</span>
      </div>
    );
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>事件 / 投递</th>
            <th>顺序号</th>
            <th>状态</th>
            <th>尝试次数</th>
            <th>更新时间</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.slice(0, expanded ? 60 : 6).map((item) => (
            <tr key={item.id}>
              <td>
                <div className="delivery-name">
                  <i>
                    <Code2 size={13} />
                  </i>
                  <span>
                    <b>{item.eventType || "Webhook 事件"}</b>
                    <small>
                      {short(item.id)} · {short(item.endpointId)}
                    </small>
                  </span>
                </div>
              </td>
              <td>
                <code className="sequence">#{item.sequenceNumber}</code>
              </td>
              <td>
                <Status status={item.status} />
              </td>
              <td>
                <span className="attempt-number">
                  {item.attemptCount}
                  <small> / {item.maxAttempts ?? "—"}</small>
                </span>
              </td>
              <td>
                <span className="table-muted">
                  {ago(item.eventCreatedAt ?? item.updatedAt)}
                </span>
              </td>
              <td>
                <div className="table-actions">
                  <button title="查看投递尝试" onClick={() => onAttempts(item)}>
                    <MoreHorizontal size={16} />
                  </button>
                  {["DEAD", "SUCCEEDED"].includes(item.status) && (
                    <button title="重新投递" onClick={() => onReplay(item)}>
                      <RotateCcw size={14} />
                    </button>
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EndpointMini({ endpoint }: { endpoint: Endpoint }) {
  let host = endpoint.url;
  try {
    host = new URL(endpoint.url).host;
  } catch {
    /* show raw URL */
  }
  return (
    <div className="endpoint-mini">
      <i>
        <Webhook size={14} />
      </i>
      <span>
        <b>{host}</b>
        <small>{endpoint.eventTypes.slice(0, 2).join(", ")}</small>
      </span>
      <Circuit state={endpoint.circuitState} />
    </div>
  );
}

function EndpointCard({
  endpoint,
  onToggle,
}: {
  endpoint: Endpoint;
  onToggle: () => void;
}) {
  let host = endpoint.url;
  try {
    host = new URL(endpoint.url).host;
  } catch {
    /* show raw URL */
  }
  return (
    <div className="panel endpoint-card">
      <div className="endpoint-card-head">
        <i>
          <Webhook size={17} />
        </i>
        <span>
          <b>{host}</b>
          <small>{endpoint.url}</small>
        </span>
        <button
          className={`switch ${endpoint.enabled ? "on" : ""}`}
          onClick={onToggle}
          aria-label={endpoint.enabled ? "暂停端点" : "恢复端点"}
        >
          <i />
        </button>
      </div>
      <div className="event-tags">
        {endpoint.eventTypes.map((type) => (
          <span key={type}>{type}</span>
        ))}
      </div>
      <div className="endpoint-details">
        <div>
          <small>熔断状态</small>
          <Circuit state={endpoint.circuitState} />
        </div>
        <div>
          <small>连续失败</small>
          <b>
            {endpoint.consecutiveFailures}
            <i> / {endpoint.circuitFailureThreshold}</i>
          </b>
        </div>
        <div>
          <small>冷却时间</small>
          <b>{endpoint.circuitOpenDurationSeconds}s</b>
        </div>
        <div>
          <small>端点状态</small>
          <b className={endpoint.enabled ? "enabled" : "paused"}>
            {endpoint.enabled ? "已启用" : "已暂停"}
          </b>
        </div>
      </div>
      <div className="endpoint-card-foot">
        <span>
          <i
            className={
              endpoint.enabled ? "status-dot" : "status-dot paused-dot"
            }
          />
          {endpoint.enabled ? "正在接收并投递事件" : "已暂停接收与待投递任务"}
        </span>
        <code>{short(endpoint.id)}</code>
      </div>
    </div>
  );
}

function Status({ status }: { status: string }) {
  return (
    <span className={`status-badge ${status.toLowerCase()}`}>
      <i />
      {statusLabel(status)}
    </span>
  );
}
function Circuit({ state }: { state: string }) {
  return (
    <span className={`circuit-badge ${state.toLowerCase()}`}>
      <i />
      {circuitLabel(state)}
    </span>
  );
}
function PipeStep({
  number,
  title,
  note,
  icon: Icon,
  state,
}: {
  number: string;
  title: string;
  note: string;
  icon: typeof Activity;
  state: string;
}) {
  return (
    <div className={`pipe-step ${state}`}>
      <i>
        <Icon size={15} />
        <small>{number}</small>
      </i>
      <span>
        <b>{title}</b>
        <small>{note}</small>
      </span>
    </div>
  );
}
function Guard({
  label,
  icon: Icon,
  tone,
}: {
  label: string;
  icon: typeof Activity;
  tone: string;
}) {
  return (
    <div className="guard-row">
      <span>
        <i className={tone}>
          <Icon size={12} />
        </i>
        {label}
      </span>
      <b>已启用</b>
    </div>
  );
}
function Empty({
  title,
  body,
  action,
  onAction,
}: {
  title: string;
  body: string;
  action: string;
  onAction: () => void;
}) {
  return (
    <div className="empty-state">
      <div className="empty-orbit">
        <Webhook size={18} />
      </div>
      <b>{title}</b>
      <span>{body}</span>
      <button className="panel-action" onClick={onAction}>
        {action}
        <ArrowRight size={14} />
      </button>
    </div>
  );
}
function Modal({
  title,
  eyebrow,
  onClose,
  children,
  wide = false,
}: {
  title: string;
  eyebrow: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  return (
    <div
      className="modal-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        className={`modal ${wide ? "wide" : ""}`}
        role="dialog"
        aria-modal="true"
      >
        <div className="modal-head">
          <div>
            <div className="section-kicker">{eyebrow}</div>
            <h2>{title}</h2>
          </div>
          <button className="icon-btn" onClick={onClose}>
            <X size={17} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}
