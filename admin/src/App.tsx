import { useEffect, useMemo, useState } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import {
  broadcastNotification,
  createChatCommand,
  clearChat,
  createUser,
  deleteReport,
  deleteReports,
  deleteDebugLogs,
  deleteChatCommand,
  deleteUser,
  getAdminFeed,
  getAdminSearch,
  getAdminHistory,
  getAdminTimeCapsules,
  getCalendar,
  getChat,
  getChatCommands,
  getDebugLogs,
  downloadDebugLogs,
  downloadPerformanceExport,
  downloadPerformanceTrackingExport,
  downloadIncidentExport,
  downloadTriggerAuditExport,
  getReports,
  getAdminPerformanceOverview,
  getAdminPerformanceRoutes,
  getAdminPerformanceTracking,
  getAdminPerformanceSlo,
  getAdminPerformanceSpikes,
  getAdminIncidentExportStatus,
  getAdminTriggerRuntime,
  getAdminTriggerAudit,
  getAdminTriggerAuditSummary,
  getSystemHealth,
  getSettings,
  getStats,
  issueUserAccessToken,
  listUsers,
  login,
  notifyUser,
  resetTodayPrompt,
  sendChat,
  triggerPrompt,
  updateAdminPerformanceTracking,
  updateAdminTriggerRuntime,
  updateCalendarDay,
  updateChatCommand,
  updateReport,
  updateSettings,
  updateUser,
  type AdminReportItem,
  type AdminPerformanceOverview,
  type AdminPerformanceSloState,
  type AdminPerformanceRouteHotspot,
  type AdminPerformanceSpikeWindow,
  type AdminPerformanceTrackingState,
  type AdminIncidentExportStatus,
  type AdminTriggerRuntimeResponse,
  type AdminTriggerAuditItem,
  type AdminTriggerAuditSummary,
  type AdminSearchResult,
  type AdminSearchScope,
  type AdminStats,
  type AdminHistoryDay,
  type AdminHistoryAnomaly,
  type AdminHistoryCohortEntry,
  type AdminHistoryConversionPoint,
  type AdminHistoryDistribution,
  type AdminHistoryLeaderboardEntry,
  type AdminHistoryReliability,
  type AdminHistoryTimeSeriesPoint,
  type ChatCommand,
  type AdminUser,
  type ChatItem,
  type CalendarItem,
  type AdminTimeCapsuleItem,
  type FeedItem,
  type DebugLogItem,
  type DebugLogsResponse,
  type MonthlyRecap,
  type Settings,
  type SystemHealth,
  type UserPromptRule,
} from "./api";

type Tab = "dashboard" | "system" | "events" | "commands" | "users" | "feed" | "chat" | "calendar" | "history" | "performance" | "incident_export" | "trigger_audit" | "time_capsule" | "reports" | "debug" | "settings";
type AdminArea = "operations" | "analytics" | "config";
type OperationsSubtab = "cockpit" | "daily_calendar" | "feed" | "chat" | "time_capsules" | "reports";
type AnalyticsSubtab = "history" | "performance" | "incident_export" | "trigger_audit" | "debug" | "system";
type ConfigSubtab = "users" | "events" | "commands" | "settings";
type AdminSubtab = OperationsSubtab | AnalyticsSubtab | ConfigSubtab;

type SavedView = {
  id: string;
  name: string;
  tab: "reports" | "debug" | "history";
  payload: Record<string, string | number | boolean>;
};

type TopAction = {
  id: string;
  label: string;
  run: () => void | Promise<void>;
  tone?: "normal" | "danger";
};

const DEFAULT_SETTINGS: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 20,
  uploadWindowMinutes: 10,
  feedCommentPreviewLimit: 10,
  performanceTrackingEnabled: false,
  performanceTrackingWindowMinutes: 30,
  performanceTrackingOneShot: false,
  promptNotificationText: "Zeit fuer dein Daily Foto",
  maxUploadBytes: 0,
  chatCommandEnabled: false,
  chatCommandValue: "-moment",
  chatCommandTrigger: true,
  chatCommandSendPush: true,
  chatCommandPushText: "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.",
  chatCommandEchoChat: true,
  chatCommandEchoText: "Moment wurde von {user} angefordert.",
  userPromptRules: [
    {
      id: "diagnostics_consent_v1",
      enabled: true,
      triggerType: "app_version",
      title: "Diagnose & Performance teilen?",
      body: "Wenn du zustimmst, sendet die App bei Problemen und Ladezeiten technische Diagnosedaten. Das hilft uns, Fehler und Engpaesse schneller zu finden. Du kannst das jederzeit im Profil widerrufen.",
      confirmLabel: "Zustimmen",
      declineLabel: "Nicht teilen",
      cooldownHours: 0,
      priority: 10,
    },
  ],
};
const cloneDefaultSettings = (): Settings => ({
  ...DEFAULT_SETTINGS,
  userPromptRules: DEFAULT_SETTINGS.userPromptRules.map((rule) => ({ ...rule })),
});
const emptySettings: Settings = cloneDefaultSettings();
const legacyNavStorageKey = "admin-legacy-nav-enabled";
const savedViewsStorageKey = "admin-saved-views-v1";

const subtabToTab: Record<AdminArea, Record<string, Tab>> = {
  operations: {
    cockpit: "dashboard",
    daily_calendar: "calendar",
    feed: "feed",
    chat: "chat",
    time_capsules: "time_capsule",
    reports: "reports",
  },
  analytics: {
    history: "history",
    performance: "performance",
    incident_export: "incident_export",
    trigger_audit: "trigger_audit",
    debug: "debug",
    system: "system",
  },
  config: {
    users: "users",
    events: "events",
    commands: "commands",
    settings: "settings",
  },
};

const areaSubtabs: Record<AdminArea, Array<{ key: AdminSubtab; label: string }>> = {
  operations: [
    { key: "cockpit", label: "Cockpit" },
    { key: "daily_calendar", label: "Daily & Kalender" },
    { key: "feed", label: "Feed" },
    { key: "chat", label: "Chat" },
    { key: "time_capsules", label: "Time-Capsules" },
    { key: "reports", label: "Reports" },
  ],
  analytics: [
    { key: "history", label: "Historie" },
    { key: "performance", label: "Performance" },
    { key: "incident_export", label: "Incident Export" },
    { key: "trigger_audit", label: "Trigger Audit" },
    { key: "debug", label: "Debug Logs" },
    { key: "system", label: "System Health" },
  ],
  config: [
    { key: "users", label: "Benutzerverwaltung" },
    { key: "events", label: "Events & Notifications" },
    { key: "commands", label: "Chat-Commands" },
    { key: "settings", label: "Einstellungen" },
  ],
};

const emptyStats: AdminStats = {
  users: 0,
  photos: 0,
  devices: 0,
  prompts: 0,
  totalImages: 0,
  runningDays: 0,
  storageBytes: 0,
};

type CommandDraft = {
  name: string;
  command: string;
  action: ChatCommand["action"];
  enabled: boolean;
  requireAdmin: boolean;
  sendPush: boolean;
  postChat: boolean;
  pushText: string;
  responseText: string;
  cooldownSecond: number;
};

const emptyCommandDraft: CommandDraft = {
  name: "",
  command: "-",
  action: "trigger_moment",
  enabled: true,
  requireAdmin: false,
  sendPush: true,
  postChat: true,
  pushText: "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.",
  responseText: "Moment wurde von {user} angefordert.",
  cooldownSecond: 0,
};

function debugMetaHint(meta: string): string {
  const normalized = meta.toLowerCase();
  if (normalized.includes("network=dns")) return "DNS-Problem";
  if (normalized.includes("network=connect")) return "Verbindungsproblem";
  if (normalized.includes("network=timeout")) return "Timeout";
  return "";
}

function tabToAreaSubtab(tab: Tab): { area: AdminArea; subtab: AdminSubtab } {
  switch (tab) {
    case "dashboard":
      return { area: "operations", subtab: "cockpit" };
    case "calendar":
      return { area: "operations", subtab: "daily_calendar" };
    case "feed":
      return { area: "operations", subtab: "feed" };
    case "chat":
      return { area: "operations", subtab: "chat" };
    case "time_capsule":
      return { area: "operations", subtab: "time_capsules" };
    case "reports":
      return { area: "operations", subtab: "reports" };
    case "history":
      return { area: "analytics", subtab: "history" };
    case "performance":
      return { area: "analytics", subtab: "performance" };
    case "incident_export":
      return { area: "analytics", subtab: "incident_export" };
    case "trigger_audit":
      return { area: "analytics", subtab: "trigger_audit" };
    case "debug":
      return { area: "analytics", subtab: "debug" };
    case "system":
      return { area: "analytics", subtab: "system" };
    case "users":
      return { area: "config", subtab: "users" };
    case "events":
      return { area: "config", subtab: "events" };
    case "commands":
      return { area: "config", subtab: "commands" };
    case "settings":
    default:
      return { area: "config", subtab: "settings" };
  }
}

function parseQueryAreaSubtab(): { area: AdminArea; subtab: AdminSubtab } | null {
  const params = new URLSearchParams(window.location.search);
  const area = (params.get("area") || "").trim() as AdminArea;
  const subtab = (params.get("subtab") || "").trim() as AdminSubtab;
  if (!area || !subtab) return null;
  if (!(area in subtabToTab)) return null;
  if (!Object.prototype.hasOwnProperty.call(subtabToTab[area], subtab)) return null;
  return { area, subtab };
}

function readSavedViews(): SavedView[] {
  try {
    const raw = localStorage.getItem(savedViewsStorageKey);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((entry) => entry && typeof entry === "object")
      .map((entry) => ({
        id: String(entry.id || `view_${Date.now()}`),
        name: String(entry.name || "Gespeicherte Ansicht"),
        tab: (entry.tab === "reports" || entry.tab === "debug" || entry.tab === "history" ? entry.tab : "history"),
        payload: typeof entry.payload === "object" && entry.payload ? entry.payload : {},
      }));
  } catch {
    return [];
  }
}

export function App() {
  const [token, setToken] = useState<string>(() => localStorage.getItem("admin-token") || "");
  const [darkMode, setDarkMode] = useState<boolean>(() => localStorage.getItem("admin-dark-mode") === "1");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [settings, setSettings] = useState<Settings>(emptySettings);
  const [savedSettings, setSavedSettings] = useState<Settings>(emptySettings);
  const [stats, setStats] = useState<AdminStats>(emptyStats);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [feedItems, setFeedItems] = useState<FeedItem[]>([]);
  const [feedMonthRecap, setFeedMonthRecap] = useState<MonthlyRecap | null>(null);
  const [chatItems, setChatItems] = useState<ChatItem[]>([]);
  const [chatDraft, setChatDraft] = useState("");
  const [systemHealth, setSystemHealth] = useState<SystemHealth | null>(null);
  const [chatCommands, setChatCommands] = useState<ChatCommand[]>([]);
  const [editingCommandId, setEditingCommandId] = useState<number | null>(null);
  const [commandDraft, setCommandDraft] = useState<CommandDraft>(emptyCommandDraft);
  const [calendarItems, setCalendarItems] = useState<CalendarItem[]>([]);
  const [calendarDrafts, setCalendarDrafts] = useState<Record<string, string>>({});
  const [historyItems, setHistoryItems] = useState<AdminHistoryDay[]>([]);
  const [historyDays, setHistoryDays] = useState<number>(30);
  const [historyOffset, setHistoryOffset] = useState<number>(0);
  const [historyTrackingSince, setHistoryTrackingSince] = useState("");
  const [expandedHistoryDays, setExpandedHistoryDays] = useState<Record<string, boolean>>({});
  const [historyReliableTop, setHistoryReliableTop] = useState<AdminHistoryLeaderboardEntry[]>([]);
  const [historyExtraHeavyTop, setHistoryExtraHeavyTop] = useState<AdminHistoryLeaderboardEntry[]>([]);
  const [historyAnomalies, setHistoryAnomalies] = useState<AdminHistoryAnomaly[]>([]);
  const [historyTimeseries, setHistoryTimeseries] = useState<AdminHistoryTimeSeriesPoint[]>([]);
  const [historyDistribution, setHistoryDistribution] = useState<AdminHistoryDistribution>({
    photoMix: { promptRatio: 0, extraRatio: 0, capsuleRatio: 0 },
    userMix: { promptRatio: 0, extraRatio: 0 },
    rawTotals: {
      photos: 0,
      dailyMomentPhotos: 0,
      extraPhotos: 0,
      capsulePhotos: 0,
      postedUsersSum: 0,
      onlineUsersSum: 0,
    },
  });
  const [historyConversion, setHistoryConversion] = useState<AdminHistoryConversionPoint[]>([]);
  const [historyReliability, setHistoryReliability] = useState<AdminHistoryReliability>({
    daysAnalyzed: 0,
    daysWithPosts: 0,
    daysWithTriggerPerformance: 0,
    onTimeTriggerDays: 0,
    onTimeTriggerRate: 0,
    avgAbsoluteTriggerDelayMinutes: 0,
    debugErrorIndicators: 0,
    errorIndicatorRatePerDay: 0,
    avgPostedUsersPerDay: 0,
    avgOnlineUsersPerDay: 0,
    avgRequestsPerOnlineUser: 0,
  });
  const [historyCohorts, setHistoryCohorts] = useState<AdminHistoryCohortEntry[]>([]);
  const [performanceFrom, setPerformanceFrom] = useState("");
  const [performanceTo, setPerformanceTo] = useState("");
  const [performanceBucket, setPerformanceBucket] = useState<"1m" | "5m">("1m");
  const [performanceOverview, setPerformanceOverview] = useState<AdminPerformanceOverview | null>(null);
  const [performanceSlo, setPerformanceSlo] = useState<AdminPerformanceSloState | null>(null);
  const [performanceRoutes, setPerformanceRoutes] = useState<AdminPerformanceRouteHotspot[]>([]);
  const [performanceSpikes, setPerformanceSpikes] = useState<AdminPerformanceSpikeWindow[]>([]);
  const [performanceTrackingEnabled, setPerformanceTrackingEnabled] = useState(false);
  const [performanceTrackingWindowMinutes, setPerformanceTrackingWindowMinutes] = useState(30);
  const [performanceTrackingOneShot, setPerformanceTrackingOneShot] = useState(false);
  const [performanceTrackingActiveSpike, setPerformanceTrackingActiveSpike] = useState<AdminPerformanceSpikeWindow | null>(null);
  const [performanceTrackingLatestSpike, setPerformanceTrackingLatestSpike] = useState<AdminPerformanceSpikeWindow | null>(null);
  const [incidentFrom, setIncidentFrom] = useState<string>(() => {
    const now = new Date();
    const from = new Date(now.getTime() - 60 * 60 * 1000);
    return toInputDateTime(from.toISOString());
  });
  const [incidentTo, setIncidentTo] = useState<string>(() => toInputDateTime(new Date().toISOString()));
  const [incidentDay, setIncidentDay] = useState<string>("");
  const [incidentIncludeGateway, setIncidentIncludeGateway] = useState<boolean>(true);
  const [incidentStatus, setIncidentStatus] = useState<AdminIncidentExportStatus | null>(null);
  const [triggerRuntime, setTriggerRuntime] = useState<AdminTriggerRuntimeResponse | null>(null);
  const [triggerRuntimeWindowMinutes, setTriggerRuntimeWindowMinutes] = useState<number>(60);
  const [triggerAuditItems, setTriggerAuditItems] = useState<AdminTriggerAuditItem[]>([]);
  const [triggerAuditSummary, setTriggerAuditSummary] = useState<AdminTriggerAuditSummary | null>(null);
  const [triggerAuditDays, setTriggerAuditDays] = useState<number>(7);
  const [triggerAuditDay, setTriggerAuditDay] = useState("");
  const [triggerAuditSource, setTriggerAuditSource] = useState("");
  const [triggerAuditResult, setTriggerAuditResult] = useState("");
  const [triggerAuditRequestId, setTriggerAuditRequestId] = useState("");
  const [triggerAuditActorUserId, setTriggerAuditActorUserId] = useState<number>(0);
  const [triggerAuditLimit, setTriggerAuditLimit] = useState<number>(200);
  const [timeCapsuleItems, setTimeCapsuleItems] = useState<AdminTimeCapsuleItem[]>([]);
  const [reports, setReports] = useState<AdminReportItem[]>([]);
  const [reportUserFilter, setReportUserFilter] = useState<number>(0);
  const [reportTypeFilter, setReportTypeFilter] = useState<"" | "bug" | "idea">("");
  const [reportStatusFilter, setReportStatusFilter] = useState<"" | "open" | "in_review" | "done" | "rejected">("");
  const [debugLogs, setDebugLogs] = useState<DebugLogItem[]>([]);
  const [debugFilterInfo, setDebugFilterInfo] = useState<{ since: string; serverNow: string; sinceHours: number }>({
    since: "",
    serverNow: "",
    sinceHours: 24,
  });
  const [debugUserFilter, setDebugUserFilter] = useState<number>(0);
  const [debugSinceHours, setDebugSinceHours] = useState<1 | 12 | 24>(24);
  const [feedDay, setFeedDay] = useState<string>(() => new Date().toISOString().slice(0, 10));
  const [message, setMessage] = useState("");
  const initialNav = useMemo(() => parseQueryAreaSubtab(), []);
  const [activeArea, setActiveArea] = useState<AdminArea>(initialNav?.area || "operations");
  const [activeSubtab, setActiveSubtab] = useState<AdminSubtab>(initialNav?.subtab || "cockpit");
  const [activeTab, setActiveTab] = useState<Tab>(() => {
    if (initialNav) {
      return subtabToTab[initialNav.area][initialNav.subtab];
    }
    return "dashboard";
  });
  const [legacyNavEnabled, setLegacyNavEnabled] = useState<boolean>(() => localStorage.getItem(legacyNavStorageKey) === "1");
  const [searchQuery, setSearchQuery] = useState("");
  const [searchScope, setSearchScope] = useState<"all" | AdminSearchScope>("all");
  const [searchResults, setSearchResults] = useState<AdminSearchResult[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [savedViews, setSavedViews] = useState<SavedView[]>(() => readSavedViews());
  const [openReportsCount, setOpenReportsCount] = useState(0);

  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newIsAdmin, setNewIsAdmin] = useState(false);

  const [resetPassword, setResetPassword] = useState<Record<number, string>>({});
  const [issuingTokenForUserId, setIssuingTokenForUserId] = useState<number>(0);
  const [broadcastBody, setBroadcastBody] = useState("Server-Test: Bitte App öffnen und Daily Foto posten.");
  const [updateNoticeVersion, setUpdateNoticeVersion] = useState("0.2.12");
  const [targetUserId, setTargetUserId] = useState<number>(0);
  const [targetUserSearch, setTargetUserSearch] = useState("");

  const isLoggedIn = useMemo(() => token.length > 0, [token]);
  const filteredTargetUsers = useMemo(() => {
    const q = targetUserSearch.trim().toLowerCase();
    if (!q) return users;
    return users.filter((u) => u.username.toLowerCase().includes(q));
  }, [users, targetUserSearch]);
  const hasReportDeleteFilter = reportUserFilter > 0 || reportTypeFilter !== "" || reportStatusFilter !== "";
  const debugSummary = useMemo(() => {
    const uniqueUsers = new Set(debugLogs.map((row) => row.user?.id).filter(Boolean)).size;
    const typeCounts = debugLogs.reduce<Record<string, number>>((acc, row) => {
      const key = row.type || "unknown";
      acc[key] = (acc[key] || 0) + 1;
      return acc;
    }, {});
    const topType = Object.entries(typeCounts).sort((a, b) => b[1] - a[1])[0]?.[0] || "-";
    return {
      total: debugLogs.length,
      uniqueUsers,
      topType,
      newestAt: debugLogs[0]?.createdAt || "",
    };
  }, [debugLogs]);
  const reduceMotion = useMemo(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return false;
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }, []);
  const historyTrendChartData = useMemo(
    () =>
      historyTimeseries.map((row) => ({
        ...row,
        dayLabel: formatDateShort(row.day),
      })),
    [historyTimeseries]
  );
  const historyCompositionChartData = useMemo(
    () =>
      historyTimeseries.map((row) => ({
        dayLabel: formatDateShort(row.day),
        dailyMomentPhotos: row.dailyMomentPhotos,
        extraPhotos: row.extraPhotos,
        capsulePhotos: row.capsulePhotos,
      })),
    [historyTimeseries]
  );
  const historyConversionChartData = useMemo(
    () =>
      historyConversion.map((row) => ({
        ...row,
        dayLabel: formatDateShort(row.day),
      })),
    [historyConversion]
  );
  const historyScatterData = useMemo(
    () =>
      historyTimeseries.map((row) => ({
        x: row.triggerDelayMin,
        y: row.postedUsers,
        dayLabel: formatDateShort(row.day),
      })),
    [historyTimeseries]
  );
  const historyPhotoMixPieData = useMemo(
    () => [
      { name: "Daily-Moment", value: ratioPercent(historyDistribution.photoMix.promptRatio) },
      { name: "Extra", value: ratioPercent(historyDistribution.photoMix.extraRatio) },
      { name: "Capsule", value: ratioPercent(historyDistribution.photoMix.capsuleRatio) },
    ],
    [historyDistribution]
  );
  const historyReliableChartData = useMemo(
    () =>
      historyReliableTop.map((row) => ({
        username: row.username,
        scorePercent: ratioPercent(row.reliabilityScore || 0),
      })),
    [historyReliableTop]
  );
  const historyExtraHeavyChartData = useMemo(
    () =>
      historyExtraHeavyTop.map((row) => ({
        username: row.username,
        scorePercent: ratioPercent(row.extraBiasScore || 0),
      })),
    [historyExtraHeavyTop]
  );
  const historyAnomalyTimelineData = useMemo(
    () =>
      historyAnomalies.map((row) => ({
        day: row.day,
        dayLabel: formatDateShort(row.day),
        severityScore: row.severity === "high" ? 3 : row.severity === "medium" ? 2 : 1,
        severity: row.severity,
        reason: row.reason,
      })),
    [historyAnomalies]
  );
  const historyCohortTrendData = useMemo(
    () =>
      historyCohorts.slice(0, 12).map((row) => ({
        username: row.username,
        participation7d: ratioPercent(row.participation7d),
        participation30d: ratioPercent(row.participation30d),
      })),
    [historyCohorts]
  );
  const performanceTrendData = useMemo(
    () =>
      (performanceOverview?.items || []).map((row) => ({
        ...row,
        bucketLabel: new Date(row.bucketStart).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
      })),
    [performanceOverview]
  );
  const performanceSystemData = useMemo(
    () =>
      (performanceOverview?.system || []).map((row) => ({
        ...row,
        bucketLabel: new Date(row.bucketStart).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
        memAllocMb: Number(row.memAllocBytes || 0) / (1024 * 1024),
        dbWaitMs: Number(row.dbWaitDurationMs || 0),
      })),
    [performanceOverview]
  );
  const performanceSpikeChartData = useMemo(
    () =>
      performanceSpikes
        .slice()
        .reverse()
        .map((row) => ({
          dayLabel: formatDateShort(row.day),
          p95PeakMs: row.p95PeakMs,
          feedReadCount: row.feedReadCount,
          uploadCount: row.uploadCount,
          errorCount: row.errorCount,
        })),
    [performanceSpikes]
  );

  useEffect(() => {
    if (!token) return;
    void loadAdminData(token);
  }, [token]);

  useEffect(() => {
    if (!token) return;
    if (activeTab === "feed") {
      void loadFeed(token, feedDay);
    }
    if (activeTab === "chat") {
      void loadChat(token);
    }
    if (activeTab === "calendar") {
      void loadCalendar(token);
    }
    if (activeTab === "history") {
      void loadHistory(token, historyDays, historyOffset);
    }
    if (activeTab === "performance") {
      void loadPerformance(token, performanceBucket, performanceFrom, performanceTo);
    }
    if (activeTab === "incident_export") {
      void loadIncidentStatus(token);
      void loadTriggerRuntime(token);
    }
    if (activeTab === "trigger_audit") {
      void loadTriggerAudit(token);
    }
    if (activeTab === "time_capsule") {
      void loadTimeCapsules(token);
    }
    if (activeTab === "reports") {
      void loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter);
    }
    if (activeTab === "commands") {
      void loadCommands(token);
    }
    if (activeTab === "system") {
      void loadSystemHealth(token);
    }
    if (activeTab === "debug") {
      void loadDebugLogs(token, debugUserFilter, debugSinceHours);
    }
  }, [token, activeTab, feedDay, debugUserFilter, debugSinceHours, reportUserFilter, reportTypeFilter, reportStatusFilter, historyDays, historyOffset, performanceBucket, performanceFrom, performanceTo, incidentFrom, incidentTo, incidentDay, incidentIncludeGateway, triggerRuntimeWindowMinutes, triggerAuditDays, triggerAuditDay, triggerAuditSource, triggerAuditResult, triggerAuditRequestId, triggerAuditActorUserId, triggerAuditLimit]);

  useEffect(() => {
    if (!token || activeTab !== "system") return;
    const id = window.setInterval(() => {
      void loadSystemHealth(token);
    }, 10000);
    return () => window.clearInterval(id);
  }, [token, activeTab]);

  useEffect(() => {
    localStorage.setItem("admin-dark-mode", darkMode ? "1" : "0");
  }, [darkMode]);

  useEffect(() => {
    localStorage.setItem(legacyNavStorageKey, legacyNavEnabled ? "1" : "0");
  }, [legacyNavEnabled]);

  useEffect(() => {
    localStorage.setItem(savedViewsStorageKey, JSON.stringify(savedViews));
  }, [savedViews]);

  useEffect(() => {
    if (legacyNavEnabled) return;
    const tab = subtabToTab[activeArea][activeSubtab];
    if (tab && tab !== activeTab) {
      setActiveTab(tab);
    }
  }, [legacyNavEnabled, activeArea, activeSubtab, activeTab]);

  useEffect(() => {
    if (legacyNavEnabled) return;
    const params = new URLSearchParams(window.location.search);
    params.set("area", activeArea);
    params.set("subtab", activeSubtab);
    window.history.replaceState({}, "", `${window.location.pathname}?${params.toString()}`);
  }, [legacyNavEnabled, activeArea, activeSubtab]);

  function navigateTab(tab: Tab) {
    setActiveTab(tab);
    const mapped = tabToAreaSubtab(tab);
    setActiveArea(mapped.area);
    setActiveSubtab(mapped.subtab);
  }

  function navigateSubtab(area: AdminArea, subtab: AdminSubtab) {
    setActiveArea(area);
    setActiveSubtab(subtab);
    const tab = subtabToTab[area][subtab];
    setActiveTab(tab);
  }

  async function runSearch() {
    const q = searchQuery.trim();
    if (!q) {
      setSearchResults([]);
      return;
    }
    setSearchLoading(true);
    try {
      const scopes = searchScope === "all" ? undefined : [searchScope];
      const items = await getAdminSearch(token, q, { scopes, limit: 16 });
      setSearchResults(items);
    } catch (err) {
      setMessage((err as Error).message);
    } finally {
      setSearchLoading(false);
    }
  }

  function onSelectSearchResult(item: AdminSearchResult) {
    navigateTab(item.target.tab);
    if (item.target.tab === "history" && item.target.day) {
      setHistoryOffset(0);
      setHistoryDays(30);
    }
    setSearchResults([]);
    setSearchQuery("");
  }

  function saveCurrentView() {
    let tab: SavedView["tab"] | null = null;
    let payload: SavedView["payload"] = {};
    if (activeTab === "history") {
      tab = "history";
      payload = { days: historyDays, offset: historyOffset };
    }
    if (activeTab === "debug") {
      tab = "debug";
      payload = { userId: debugUserFilter, sinceHours: debugSinceHours };
    }
    if (activeTab === "reports") {
      tab = "reports";
      payload = { userId: reportUserFilter, type: reportTypeFilter, status: reportStatusFilter };
    }
    if (!tab) {
      setMessage("Saved Views gibt es fuer Reports, Debug und Historie.");
      return;
    }
    const name = window.prompt("Name fuer diese Ansicht:", `${tab} view`)?.trim();
    if (!name) return;
    const next: SavedView = {
      id: `view_${Date.now()}`,
      name,
      tab,
      payload,
    };
    setSavedViews((prev) => [next, ...prev].slice(0, 30));
    setMessage("Ansicht gespeichert.");
  }

  function applySavedView(view: SavedView) {
    if (view.tab === "history") {
      setHistoryDays(Number(view.payload.days || 30));
      setHistoryOffset(Number(view.payload.offset || 0));
      navigateTab("history");
      return;
    }
    if (view.tab === "debug") {
      const hours = Number(view.payload.sinceHours || 24);
      setDebugUserFilter(Number(view.payload.userId || 0));
      setDebugSinceHours(hours === 1 || hours === 12 || hours === 24 ? hours : 24);
      navigateTab("debug");
      return;
    }
    setReportUserFilter(Number(view.payload.userId || 0));
    setReportTypeFilter((view.payload.type as "" | "bug" | "idea") || "");
    setReportStatusFilter((view.payload.status as "" | "open" | "in_review" | "done" | "rejected") || "");
    navigateTab("reports");
  }

  async function loadAdminData(authToken: string) {
    try {
      const [s, st, u, cmds, cal, sys, openReports, triggerSummary, runtime] = await Promise.all([
        getSettings(authToken),
        getStats(authToken),
        listUsers(authToken),
        getChatCommands(authToken),
        getCalendar(authToken, 7),
        getSystemHealth(authToken),
        getReports(authToken, { status: "open", limit: 200 }),
        getAdminTriggerAuditSummary(authToken, 7),
        getAdminTriggerRuntime(authToken, { windowMinutes: triggerRuntimeWindowMinutes }),
      ]);
      setSettings(s);
      setSavedSettings(s);
      setPerformanceTrackingEnabled(Boolean(s.performanceTrackingEnabled));
      setPerformanceTrackingWindowMinutes(Number(s.performanceTrackingWindowMinutes || 30));
      setPerformanceTrackingOneShot(Boolean(s.performanceTrackingOneShot));
      setStats(st);
      setUsers(u);
      setChatCommands(cmds);
      setCalendarItems(cal);
      setCalendarDrafts(
        cal.reduce<Record<string, string>>((acc, item) => {
          acc[item.day] = toInputDateTime(item.plannedAt);
          return acc;
        }, {})
      );
      setSystemHealth(sys);
      setOpenReportsCount(openReports.length);
      setTriggerAuditSummary(triggerSummary);
      setTriggerRuntime(runtime);
    } catch (err) {
      setMessage((err as Error).message);
      setToken("");
    }
  }

  async function loadFeed(authToken: string, day?: string) {
    try {
      const data = await getAdminFeed(authToken, day);
      setFeedItems(data.items || []);
      setFeedMonthRecap(data.monthRecap || null);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadChat(authToken: string) {
    try {
      const items = await getChat(authToken);
      setChatItems(items);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadCommands(authToken: string) {
    try {
      const items = await getChatCommands(authToken);
      setChatCommands(items);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadSystemHealth(authToken: string) {
    try {
      const status = await getSystemHealth(authToken);
      setSystemHealth(status);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadDebugLogs(authToken: string, userId?: number, sinceHours: 1 | 12 | 24 = 24) {
    try {
      const response: DebugLogsResponse = await getDebugLogs(authToken, userId && userId > 0 ? userId : undefined, 200, sinceHours);
      setDebugLogs(response.items);
      setDebugFilterInfo({
        since: response.since,
        serverNow: response.serverNow,
        sinceHours: response.sinceHours,
      });
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadReports(
    authToken: string,
    userId?: number,
    type: "" | "bug" | "idea" = "",
    status: "" | "open" | "in_review" | "done" | "rejected" = ""
  ) {
    try {
      const items = await getReports(authToken, {
        userId: userId && userId > 0 ? userId : undefined,
        type,
        status,
        limit: 200,
      });
      setReports(items);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onUpdateReportStatus(
    id: number,
    status: "open" | "in_review" | "done" | "rejected",
    githubIssueNumber?: number | null
  ) {
    try {
      const updated = await updateReport(token, id, { status, githubIssueNumber: githubIssueNumber ?? null });
      setReports((prev) => prev.map((item) => (item.id === id ? updated : item)));
      setMessage("Report aktualisiert.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDeleteReport(id: number) {
    const confirmed = window.confirm("Diesen Report wirklich loeschen?");
    if (!confirmed) return;
    setMessage("");
    try {
      await deleteReport(token, id);
      await loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter);
      setMessage("Report geloescht.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDeleteFilteredReports() {
    if (!hasReportDeleteFilter) {
      setMessage("Bitte erst mindestens einen Report-Filter setzen.");
      return;
    }
    const scopeParts: string[] = [];
    if (reportTypeFilter) scopeParts.push(reportTypeFilter === "bug" ? "Bug-Reports" : "Ideen");
    if (reportStatusFilter) {
      const label =
        reportStatusFilter === "open"
          ? "Status offen"
          : reportStatusFilter === "in_review"
            ? "Status in Bearbeitung"
            : reportStatusFilter === "done"
              ? "Status erledigt"
              : "Status abgelehnt";
      scopeParts.push(label);
    }
    if (reportUserFilter > 0) {
      scopeParts.push(`@${users.find((u) => u.id === reportUserFilter)?.username || `User ${reportUserFilter}`}`);
    }
    const scopeLabel = scopeParts.join(", ");
    const confirmed = window.confirm(`Wirklich alle Reports mit diesem Filter loeschen: ${scopeLabel}?`);
    if (!confirmed) return;
    setMessage("");
    try {
      const result = await deleteReports(token, {
        userId: reportUserFilter > 0 ? reportUserFilter : undefined,
        type: reportTypeFilter,
        status: reportStatusFilter,
      });
      await loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter);
      setMessage(`${result.deletedCount} Reports geloescht.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadUserLogs(hours: 1 | 12 | 24, format: "csv" | "json" = "csv") {
    if (debugUserFilter <= 0) {
      setMessage("Bitte erst einen Nutzer auswaehlen.");
      return;
    }
    try {
      await downloadDebugLogs(token, { userId: debugUserFilter, sinceHours: hours, format });
      setMessage(`Nutzer-Logs (${hours}h, ${format.toUpperCase()}) wurden heruntergeladen.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadAllLogs(hours: 1 | 12 | 24, format: "csv" | "json" = "csv") {
    try {
      await downloadDebugLogs(token, { sinceHours: hours, format });
      setMessage(`Gesamte Logs (${hours}h, ${format.toUpperCase()}) wurden heruntergeladen.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadPerformance(format: "csv" | "json" = "json") {
    try {
      const fromIso = performanceFrom ? new Date(performanceFrom).toISOString() : undefined;
      const toIso = performanceTo ? new Date(performanceTo).toISOString() : undefined;
      await downloadPerformanceExport(token, { from: fromIso, to: toIso, format });
      setMessage(`Performance-Export (${format.toUpperCase()}) wurde heruntergeladen.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDeleteDebugLogs() {
    const scopeLabel =
      debugUserFilter > 0
        ? `@${users.find((u) => u.id === debugUserFilter)?.username || `User ${debugUserFilter}`}`
        : "alle Nutzer";
    const confirmed = window.confirm(
      `Willst du wirklich die aktuell gefilterten Debug-Logs der letzten ${debugSinceHours}h fuer ${scopeLabel} loeschen?`
    );
    if (!confirmed) return;
    setMessage("");
    try {
      const result = await deleteDebugLogs(token, {
        userId: debugUserFilter > 0 ? debugUserFilter : undefined,
        sinceHours: debugSinceHours,
      });
      await loadDebugLogs(token, debugUserFilter, debugSinceHours);
      setMessage(`${result.deletedCount} Debug-Logs geloescht.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onClearChat() {
    if (!confirm("Chat wirklich komplett leeren?")) return;
    setMessage("");
    try {
      await clearChat(token);
      setChatItems([]);
      setMessage("Chat wurde geleert.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSendAdminChat(e: React.FormEvent) {
    e.preventDefault();
    const text = chatDraft.trim();
    if (!text) return;
    setMessage("");
    try {
      const result = await sendChat(token, text);
      setChatDraft("");
      if (result.report) {
        await loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter);
        setMessage(result.message || "Report wurde an den Server geschickt.");
      } else {
        await loadChat(token);
        setMessage(result.message || "Nachricht in Chat gesendet.");
      }
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadCalendar(authToken: string) {
    try {
      const items = await getCalendar(authToken, 7);
      setCalendarItems(items);
      setCalendarDrafts(
        items.reduce<Record<string, string>>((acc, item) => {
          acc[item.day] = toInputDateTime(item.plannedAt);
          return acc;
        }, {})
      );
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadHistory(authToken: string, days = 30, offset = 0) {
    try {
      const data = await getAdminHistory(authToken, days, offset);
      setHistoryItems(data.items || []);
      setHistoryTrackingSince(data.onlineTrackingSince || "");
      setHistoryReliableTop(data.leaderboard?.reliableTop || []);
      setHistoryExtraHeavyTop(data.leaderboard?.extraHeavyTop || []);
      setHistoryAnomalies(data.anomalies || []);
      setHistoryTimeseries(data.timeseries || []);
      setHistoryDistribution(
        data.distribution || {
          photoMix: { promptRatio: 0, extraRatio: 0, capsuleRatio: 0 },
          userMix: { promptRatio: 0, extraRatio: 0 },
          rawTotals: {
            photos: 0,
            dailyMomentPhotos: 0,
            extraPhotos: 0,
            capsulePhotos: 0,
            postedUsersSum: 0,
            onlineUsersSum: 0,
          },
        }
      );
      setHistoryConversion(data.conversion || []);
      setHistoryReliability(
        data.reliability || {
          daysAnalyzed: 0,
          daysWithPosts: 0,
          daysWithTriggerPerformance: 0,
          onTimeTriggerDays: 0,
          onTimeTriggerRate: 0,
          avgAbsoluteTriggerDelayMinutes: 0,
          debugErrorIndicators: 0,
          errorIndicatorRatePerDay: 0,
          avgPostedUsersPerDay: 0,
          avgOnlineUsersPerDay: 0,
          avgRequestsPerOnlineUser: 0,
        }
      );
      setHistoryCohorts(data.cohorts || []);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadPerformance(authToken: string, bucket: "1m" | "5m", fromInput = "", toInput = "") {
    try {
      const fromIso = fromInput ? new Date(fromInput).toISOString() : undefined;
      const toIso = toInput ? new Date(toInput).toISOString() : undefined;
      const [overview, routes, spikes, tracking] = await Promise.all([
        getAdminPerformanceOverview(authToken, { bucket, from: fromIso, to: toIso }),
        getAdminPerformanceRoutes(authToken, { from: fromIso, to: toIso, top: 20 }),
        getAdminPerformanceSpikes(authToken, 14),
        getAdminPerformanceTracking(authToken),
      ]);
      setPerformanceOverview(overview);
      setPerformanceRoutes(routes.items || []);
      setPerformanceSpikes(spikes.items || []);
      setPerformanceTrackingEnabled(Boolean(tracking.enabled));
      setPerformanceTrackingWindowMinutes(Number(tracking.windowMinutes || 30));
      setPerformanceTrackingOneShot(Boolean(tracking.oneShot));
      setPerformanceTrackingActiveSpike(tracking.activeSpike || null);
      setPerformanceTrackingLatestSpike(tracking.latestSpike || null);
      if (overview.slo) {
        setPerformanceSlo(overview.slo);
      } else {
        const fallbackSlo = await getAdminPerformanceSlo(authToken, 30);
        setPerformanceSlo(fallbackSlo);
      }
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSavePerformanceTracking() {
    try {
      const next: AdminPerformanceTrackingState = await updateAdminPerformanceTracking(token, {
        enabled: performanceTrackingEnabled,
        windowMinutes: performanceTrackingWindowMinutes,
        oneShot: performanceTrackingOneShot,
      });
      setPerformanceTrackingEnabled(Boolean(next.enabled));
      setPerformanceTrackingWindowMinutes(Number(next.windowMinutes || 30));
      setPerformanceTrackingOneShot(Boolean(next.oneShot));
      setPerformanceTrackingActiveSpike(next.activeSpike || null);
      setPerformanceTrackingLatestSpike(next.latestSpike || null);
      setSettings((prev) => ({
        ...prev,
        performanceTrackingEnabled: Boolean(next.enabled),
        performanceTrackingWindowMinutes: Number(next.windowMinutes || 30),
        performanceTrackingOneShot: Boolean(next.oneShot),
      }));
      setSavedSettings((prev) => ({
        ...prev,
        performanceTrackingEnabled: Boolean(next.enabled),
        performanceTrackingWindowMinutes: Number(next.windowMinutes || 30),
        performanceTrackingOneShot: Boolean(next.oneShot),
      }));
      setMessage("Daily-Tracking gespeichert.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadTrackingExport() {
    try {
      if (performanceTrackingActiveSpike?.id) {
        await downloadPerformanceTrackingExport(token, { eventId: performanceTrackingActiveSpike.id, bucket: performanceBucket });
      } else if (performanceTrackingLatestSpike?.id) {
        await downloadPerformanceTrackingExport(token, { eventId: performanceTrackingLatestSpike.id, bucket: performanceBucket });
      } else {
        await downloadPerformanceTrackingExport(token, { bucket: performanceBucket });
      }
      setMessage("Daily-Tracking JSON wurde heruntergeladen.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadTriggerAudit(authToken: string) {
    try {
      const [itemsResp, summaryResp] = await Promise.all([
        getAdminTriggerAudit(authToken, {
          day: triggerAuditDay || undefined,
          source: triggerAuditSource || undefined,
          result: triggerAuditResult || undefined,
          actorUserId: triggerAuditActorUserId > 0 ? triggerAuditActorUserId : undefined,
          requestId: triggerAuditRequestId.trim() || undefined,
          limit: triggerAuditLimit,
        }),
        getAdminTriggerAuditSummary(authToken, triggerAuditDays),
      ]);
      setTriggerAuditItems(itemsResp.items || []);
      setTriggerAuditSummary(summaryResp);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadTriggerAudit(format: "json" | "csv" = "json") {
    try {
      await downloadTriggerAuditExport(token, {
        day: triggerAuditDay || undefined,
        source: triggerAuditSource || undefined,
        result: triggerAuditResult || undefined,
        actorUserId: triggerAuditActorUserId > 0 ? triggerAuditActorUserId : undefined,
        requestId: triggerAuditRequestId.trim() || undefined,
        format,
      });
      setMessage(`Trigger-Audit Export (${format.toUpperCase()}) wurde heruntergeladen.`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadIncidentStatus(authToken: string) {
    try {
      const fromIso = incidentFrom ? new Date(incidentFrom).toISOString() : undefined;
      const toIso = incidentTo ? new Date(incidentTo).toISOString() : undefined;
      const status = await getAdminIncidentExportStatus(authToken, {
        from: fromIso,
        to: toIso,
        day: incidentDay || undefined,
        includeGateway: incidentIncludeGateway,
      });
      setIncidentStatus(status);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function loadTriggerRuntime(authToken: string) {
    try {
      const runtime = await getAdminTriggerRuntime(authToken, {
        windowMinutes: triggerRuntimeWindowMinutes,
      });
      setTriggerRuntime(runtime);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onUpdateTriggerRuntime(action: "pause" | "unpause" | "release_lease", reason?: string) {
    try {
      const runtime = await updateAdminTriggerRuntime(token, {
        action,
        reason: reason?.trim() || undefined,
      });
      setTriggerRuntime(runtime);
      if (action === "pause") {
        setMessage("Scheduler wurde pausiert.");
      } else if (action === "unpause") {
        setMessage("Scheduler wurde fortgesetzt.");
      } else {
        setMessage("Scheduler-Lease wurde freigegeben.");
      }
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDownloadIncidentBundle() {
    try {
      const fromIso = incidentFrom ? new Date(incidentFrom).toISOString() : undefined;
      const toIso = incidentTo ? new Date(incidentTo).toISOString() : undefined;
      await downloadIncidentExport(token, {
        from: fromIso,
        to: toIso,
        day: incidentDay || undefined,
        includeGateway: incidentIncludeGateway,
      });
      setMessage("Incident-Export (JSON) wurde heruntergeladen.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  function openIncidentExportWithRecentWindow(minutes = 60) {
    const now = new Date();
    const from = new Date(now.getTime() - Math.max(5, minutes) * 60 * 1000);
    setIncidentFrom(toInputDateTime(from.toISOString()));
    setIncidentTo(toInputDateTime(now.toISOString()));
    setIncidentDay("");
    navigateTab("incident_export");
  }

  async function loadTimeCapsules(authToken: string) {
    try {
      const items = await getAdminTimeCapsules(authToken);
      setTimeCapsuleItems(items);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function refreshAll() {
    if (!token) return;
    await loadAdminData(token);
    if (activeTab === "feed") await loadFeed(token, feedDay);
    if (activeTab === "chat") await loadChat(token);
    if (activeTab === "calendar") await loadCalendar(token);
    if (activeTab === "history") await loadHistory(token, historyDays, historyOffset);
    if (activeTab === "performance") await loadPerformance(token, performanceBucket, performanceFrom, performanceTo);
    if (activeTab === "incident_export") {
      await loadIncidentStatus(token);
      await loadTriggerRuntime(token);
    }
    if (activeTab === "trigger_audit") await loadTriggerAudit(token);
    if (activeTab === "time_capsule") await loadTimeCapsules(token);
    if (activeTab === "debug") await loadDebugLogs(token, debugUserFilter, debugSinceHours);
    if (activeTab === "reports") await loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter);
    if (activeTab === "commands") await loadCommands(token);
    if (activeTab === "system") await loadSystemHealth(token);
  }

  function commandPayloadFromDraft(d: CommandDraft) {
    return {
      name: d.name,
      command: d.command,
      action: d.action,
      enabled: d.enabled,
      requireAdmin: d.requireAdmin,
      sendPush: d.sendPush,
      postChat: d.postChat,
      pushText: d.pushText,
      responseText: d.responseText,
      cooldownSecond: d.cooldownSecond,
    };
  }

  async function onSaveCommand(e: React.FormEvent) {
    e.preventDefault();
    setMessage("");
    try {
      if (editingCommandId == null) {
        await createChatCommand(token, commandPayloadFromDraft(commandDraft));
        setMessage("Command erstellt.");
      } else {
        await updateChatCommand(token, editingCommandId, commandPayloadFromDraft(commandDraft));
        setMessage("Command aktualisiert.");
      }
      setEditingCommandId(null);
      setCommandDraft(emptyCommandDraft);
      await loadCommands(token);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  function onEditCommand(cmd: ChatCommand) {
    setEditingCommandId(cmd.id);
    setCommandDraft({
      name: cmd.name,
      command: cmd.command,
      action: cmd.action,
      enabled: cmd.enabled,
      requireAdmin: cmd.requireAdmin,
      sendPush: cmd.sendPush,
      postChat: cmd.postChat,
      pushText: cmd.pushText || "",
      responseText: cmd.responseText || "",
      cooldownSecond: cmd.cooldownSecond || 0,
    });
    navigateTab("commands");
  }

  async function onDeleteCommand(cmd: ChatCommand) {
    if (!confirm(`Command ${cmd.command} wirklich loeschen?`)) return;
    setMessage("");
    try {
      await deleteChatCommand(token, cmd.id);
      if (editingCommandId === cmd.id) {
        setEditingCommandId(null);
        setCommandDraft(emptyCommandDraft);
      }
      setMessage("Command geloescht.");
      await loadCommands(token);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSaveCalendarDay(day: string) {
    const value = calendarDrafts[day];
    if (!value) {
      setMessage("Zeit fehlt");
      return;
    }
    setMessage("");
    try {
      await updateCalendarDay(token, day, value);
      setMessage(`Kalender fuer ${day} gespeichert`);
      await loadCalendar(token);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onLogin(e: React.FormEvent) {
    e.preventDefault();
    setMessage("");
    try {
      const res = await login(username, password);
      if (!res.user.isAdmin) throw new Error("Kein Admin-Account");
      setToken(res.token);
      localStorage.setItem("admin-token", res.token);
      setPassword("");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSaveSettings(e: React.FormEvent) {
    e.preventDefault();
    setMessage("");
    try {
      const next = await updateSettings(token, settings);
      setSettings(next);
      setSavedSettings(next);
      setMessage("Settings gespeichert");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  function addUserPromptRule() {
    setSettings((prev) => ({
      ...prev,
      userPromptRules: [
        ...prev.userPromptRules,
        {
          id: `rule_${Date.now()}`,
          enabled: true,
          triggerType: "app_version",
          title: "",
          body: "",
          confirmLabel: "Zustimmen",
          declineLabel: "Nicht teilen",
          cooldownHours: 0,
          priority: 1,
        },
      ],
    }));
  }

  function updateUserPromptRule(index: number, patch: Partial<UserPromptRule>) {
    setSettings((prev) => ({
      ...prev,
      userPromptRules: prev.userPromptRules.map((rule, idx) => (idx === index ? { ...rule, ...patch } : rule)),
    }));
  }

  function removeUserPromptRule(index: number) {
    setSettings((prev) => ({
      ...prev,
      userPromptRules: prev.userPromptRules.filter((_, idx) => idx !== index),
    }));
  }

  async function onApplyDefaultSettings() {
    setMessage("");
    try {
      const next = await updateSettings(token, cloneDefaultSettings());
      setSettings(next);
      setSavedSettings(next);
      setMessage("Standard-Einstellungen wurden gesetzt.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function runQuickAction(action: TopAction) {
    setMessage("");
    try {
      await action.run();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  const todayCalendar = calendarItems.find((item) => item.day === new Date().toISOString().slice(0, 10)) || calendarItems[0];
  const quickActions: TopAction[] = [
    { id: "trigger", label: "Daily jetzt ausloesen", run: () => onTriggerEvent() },
    { id: "reset", label: "Heutigen Tag resetten", run: () => onResetToday(), tone: "danger" },
    {
      id: "scheduler_pause",
      label: "Scheduler pausieren",
      run: () => onUpdateTriggerRuntime("pause", "manual_admin_pause"),
      tone: "danger",
    },
    {
      id: "scheduler_unpause",
      label: "Scheduler fortsetzen",
      run: () => onUpdateTriggerRuntime("unpause"),
    },
    {
      id: "lease_release",
      label: "Lease freigeben",
      run: () => onUpdateTriggerRuntime("release_lease"),
      tone: "danger",
    },
    { id: "broadcast", label: "Broadcast senden", run: () => onBroadcast() },
    { id: "debug_export", label: "Debug JSON exportieren", run: () => onDownloadAllLogs(24, "json") },
  ];

  async function onTriggerEvent(opts?: { silent?: boolean; notifyUserIds?: number[] }) {
    setMessage("");
    try {
      const result = await triggerPrompt(token, opts);
      if (result.mode === "silent") {
        setMessage("Interner Daily-Test ausgelöst (silent, ohne Push an alle).");
      } else if (result.mode === "targeted_users") {
        setMessage(`Interner Daily-Test ausgelöst. Push nur an Zielnutzer gesendet (sent=${result.sentTo || 0}, failed=${result.failed || 0}).`);
      } else {
        setMessage("Daily Event ausgelöst. Nutzer können Prompt-Fotos hochladen.");
      }
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onResetToday() {
    if (!confirm("Wirklich den heutigen Tag zurücksetzen? Alle heutigen Fotos werden gelöscht.")) return;
    setMessage("");
    try {
      const res = await resetTodayPrompt(token);
      setMessage(`${res.message} (${res.day})`);
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onBroadcast() {
    setMessage("");
    try {
      const result = await broadcastNotification(token, broadcastBody);
      setMessage(`Benachrichtigung an ${result.sentTo} Geräte gesendet (Provider: ${result.provider}).`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onNotifySingleUser() {
    if (!targetUserId) {
      setMessage("Bitte einen Benutzer auswählen.");
      return;
    }
    setMessage("");
    try {
      const result = await notifyUser(token, targetUserId, broadcastBody);
      setMessage(
        `Benachrichtigung an ${result.username}: sent=${result.sentTo}, failed=${result.failed}, devices=${result.devices}, provider=${result.provider}.`
      );
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSendUpdateNotice() {
    const text = `Update verfügbar: Version ${updateNoticeVersion}. Bitte App aktualisieren.`;
    setBroadcastBody(text);
    try {
      const result = await broadcastNotification(token, text);
      setMessage(`Update-Hinweis an ${result.sentTo} Geräte gesendet (Provider: ${result.provider}).`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onCreateUser(e: React.FormEvent) {
    e.preventDefault();
    setMessage("");
    try {
      await createUser(token, newUsername, newPassword, newIsAdmin);
      setMessage("User angelegt");
      setNewUsername("");
      setNewPassword("");
      setNewIsAdmin(false);
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onToggleAdmin(user: AdminUser, isAdmin: boolean) {
    setMessage("");
    try {
      await updateUser(token, user.id, { isAdmin });
      setMessage(`Rolle für ${user.username} aktualisiert`);
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onResetPassword(user: AdminUser) {
    const pwd = resetPassword[user.id]?.trim();
    if (!pwd) {
      setMessage("Neues Passwort fehlt");
      return;
    }
    setMessage("");
    try {
      await updateUser(token, user.id, { password: pwd });
      setResetPassword((prev) => ({ ...prev, [user.id]: "" }));
      setMessage(`Passwort für ${user.username} geändert`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onDeleteUser(user: AdminUser) {
    if (!confirm(`User ${user.username} wirklich löschen?`)) return;
    setMessage("");
    try {
      await deleteUser(token, user.id);
      setMessage(`User ${user.username} gelöscht`);
      await refreshAll();
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function copyToClipboard(text: string) {
    if (!text) throw new Error("Kein Text zum Kopieren vorhanden.");
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return;
    }
    const ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "true");
    ta.style.position = "absolute";
    ta.style.left = "-9999px";
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand("copy");
    document.body.removeChild(ta);
    if (!ok) throw new Error("Kopieren nicht möglich.");
  }

  async function onCopyAdminToken() {
    setMessage("");
    try {
      await copyToClipboard(token);
      setMessage("Admin-Token kopiert.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onCopyUserToken(user: AdminUser) {
    if (!token) return;
    setMessage("");
    setIssuingTokenForUserId(user.id);
    try {
      const issued = await issueUserAccessToken(token, user.id);
      await copyToClipboard(issued.token);
      const expiry = issued.expiresAt ? ` (gültig bis ${formatDateTime(issued.expiresAt)})` : "";
      setMessage(`Token für ${user.username} kopiert${expiry}.`);
    } catch (err) {
      setMessage((err as Error).message);
    } finally {
      setIssuingTokenForUserId(0);
    }
  }

  function logout() {
    localStorage.removeItem("admin-token");
    setToken("");
    setMessage("");
  }

  if (!isLoggedIn) {
    return (
      <main className={`page ${darkMode ? "theme-dark" : ""}`}>
        <section className="panel">
          <div className="row">
            <h1>Daily Admin</h1>
            <button type="button" onClick={() => setDarkMode((v) => !v)}>
              {darkMode ? "Light" : "Dark"}
            </button>
          </div>
          <form onSubmit={onLogin} className="stack">
            <label>
              Benutzername
              <input value={username} onChange={(e) => setUsername(e.target.value)} required />
            </label>
            <label>
              Passwort
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </label>
            <button type="submit">Einloggen</button>
          </form>
          {message && <p className="message">{message}</p>}
        </section>
      </main>
    );
  }

  return (
    <main className={`page ${darkMode ? "theme-dark" : ""}`}>
      <section className="panel wide">
        <div className="row topbar">
          <h1>Admin Panel</h1>
          <div className="row topbar-actions">
            <button onClick={() => setDarkMode((v) => !v)}>{darkMode ? "Light" : "Dark"}</button>
            <button onClick={refreshAll}>Reload</button>
            <button onClick={() => setLegacyNavEnabled((v) => !v)}>{legacyNavEnabled ? "Neue IA" : "Legacy Tabs"}</button>
            <button onClick={logout}>Logout</button>
          </div>
        </div>

        <div className="admin-shell-head">
          <div className="search-box">
            <input
              placeholder="Suche: Nutzer, Reports, Commands, Historie..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void runSearch();
                }
              }}
            />
            <select value={searchScope} onChange={(e) => setSearchScope(e.target.value as "all" | AdminSearchScope)}>
              <option value="all">Alle Bereiche</option>
              <option value="users">Nutzer</option>
              <option value="reports">Reports</option>
              <option value="commands">Commands</option>
              <option value="history">Historie</option>
            </select>
            <button onClick={() => void runSearch()}>{searchLoading ? "Suche..." : "Suchen"}</button>
          </div>
          <div className="saved-views">
            <button onClick={saveCurrentView}>View speichern</button>
            {savedViews.length > 0 && (
              <select
                defaultValue=""
                onChange={(e) => {
                  const id = e.target.value;
                  if (!id) return;
                  const view = savedViews.find((entry) => entry.id === id);
                  if (view) applySavedView(view);
                  e.currentTarget.value = "";
                }}
              >
                <option value="">Saved Views laden</option>
                {savedViews.map((view) => (
                  <option key={view.id} value={view.id}>
                    {view.name}
                  </option>
                ))}
              </select>
            )}
          </div>
          {searchResults.length > 0 && (
            <div className="search-results">
              {searchResults.map((item, idx) => (
                <button key={`${item.type}-${item.id}-${idx}`} className="search-result-item" onClick={() => onSelectSearchResult(item)}>
                  <strong>{item.label}</strong>
                  <span className="small">
                    {item.type}
                    {item.meta ? ` · ${item.meta}` : ""}
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>

        {legacyNavEnabled ? (
          <div className="tabs">
            <button className={activeTab === "dashboard" ? "tab active" : "tab"} onClick={() => navigateTab("dashboard")}>Dashboard</button>
            <button className={activeTab === "system" ? "tab active" : "tab"} onClick={() => navigateTab("system")}>System Health</button>
            <button className={activeTab === "events" ? "tab active" : "tab"} onClick={() => navigateTab("events")}>Events & Notifications</button>
            <button className={activeTab === "commands" ? "tab active" : "tab"} onClick={() => navigateTab("commands")}>Chat-Commands</button>
            <button className={activeTab === "users" ? "tab active" : "tab"} onClick={() => navigateTab("users")}>Benutzerverwaltung</button>
            <button className={activeTab === "feed" ? "tab active" : "tab"} onClick={() => navigateTab("feed")}>Feed</button>
            <button className={activeTab === "chat" ? "tab active" : "tab"} onClick={() => navigateTab("chat")}>Chat</button>
            <button className={activeTab === "calendar" ? "tab active" : "tab"} onClick={() => navigateTab("calendar")}>Kalender</button>
            <button className={activeTab === "history" ? "tab active" : "tab"} onClick={() => navigateTab("history")}>Historie</button>
            <button className={activeTab === "performance" ? "tab active" : "tab"} onClick={() => navigateTab("performance")}>Performance</button>
            <button className={activeTab === "incident_export" ? "tab active" : "tab"} onClick={() => navigateTab("incident_export")}>Incident Export</button>
            <button className={activeTab === "trigger_audit" ? "tab active" : "tab"} onClick={() => navigateTab("trigger_audit")}>Trigger Audit</button>
            <button className={activeTab === "time_capsule" ? "tab active" : "tab"} onClick={() => navigateTab("time_capsule")}>Time-Capsule</button>
            <button className={activeTab === "reports" ? "tab active" : "tab"} onClick={() => navigateTab("reports")}>Reports</button>
            <button className={activeTab === "debug" ? "tab active" : "tab"} onClick={() => navigateTab("debug")}>Debug</button>
            <button className={activeTab === "settings" ? "tab active" : "tab"} onClick={() => navigateTab("settings")}>Einstellungen</button>
          </div>
        ) : (
          <div className="ia-nav">
            <div className="ia-primary">
              <button className={activeArea === "operations" ? "tab active" : "tab"} onClick={() => navigateSubtab("operations", "cockpit")}>Operations</button>
              <button className={activeArea === "analytics" ? "tab active" : "tab"} onClick={() => navigateSubtab("analytics", "history")}>Analyse</button>
              <button className={activeArea === "config" ? "tab active" : "tab"} onClick={() => navigateSubtab("config", "users")}>Konfiguration</button>
            </div>
            <div className="ia-subtabs">
              {areaSubtabs[activeArea].map((entry) => {
                const isActive = activeSubtab === entry.key;
                return (
                  <button key={entry.key} className={isActive ? "tab active" : "tab"} onClick={() => navigateSubtab(activeArea, entry.key)}>
                    {entry.label}
                  </button>
                );
              })}
            </div>
          </div>
        )}

        {activeTab === "dashboard" && (
          <div className="stack">
            {Number(triggerAuditSummary?.summary?.duplicateAttempts || 0) > 0 && (
              <article className="history-chart-card" style={{ borderColor: "#c74444" }}>
                <h3 style={{ color: "#ff6f6f" }}>Alarm: Mehrfach-Trigger erkannt</h3>
                <p className="small">
                  In den letzten {triggerAuditDays} Tagen gab es {triggerAuditSummary?.summary?.duplicateAttempts} zusaetzliche Trigger-Versuche
                  auf bereits ausgeloesten Tagen. Bitte Trigger Audit pruefen.
                </p>
                <div className="row">
                  <button onClick={() => navigateTab("trigger_audit")}>Zum Trigger Audit</button>
                  <button onClick={() => openIncidentExportWithRecentWindow(60)}>Jetzt Incident-Export</button>
                </div>
              </article>
            )}
            <div className="row">
              <h2>Ops Cockpit</h2>
              <div className="row">
                {quickActions.map((action) => (
                  <button
                    key={action.id}
                    className={action.tone === "danger" ? "danger" : ""}
                    onClick={() => void runQuickAction(action)}
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            </div>
            <div className="grid4">
              <article className="stat clickable" onClick={() => navigateTab("system")}>
                <h3>Serverzustand</h3>
                <p>{systemHealth?.ok ? "OK" : "CHECK"}</p>
              </article>
              <article className="stat clickable" onClick={() => navigateTab("reports")}>
                <h3>Offene Reports</h3>
                <p>{openReportsCount}</p>
              </article>
              <article className="stat clickable" onClick={() => navigateTab("debug")}>
                <h3>Debug-Fehlerindikatoren</h3>
                <p>{historyItems.reduce((acc, row) => acc + Number(row.debugErrorCount || 0), 0)}</p>
              </article>
              <article className="stat clickable" onClick={() => navigateTab("calendar")}>
                <h3>Heutiges Daily-Fenster</h3>
                <p>{todayCalendar?.uploadUntil ? formatDateTime(todayCalendar.uploadUntil) : "-"}</p>
              </article>
            </div>
            <article className="settings-current">
              <h3>Heute</h3>
              <div className="settings-grid">
                <p><strong>Tag:</strong> {todayCalendar?.day || "-"}</p>
                <p><strong>Geplant:</strong> {todayCalendar?.plannedAt ? formatDateTime(todayCalendar.plannedAt) : "-"}</p>
                <p><strong>Ausgeloest:</strong> {todayCalendar?.triggeredAt ? formatDateTime(todayCalendar.triggeredAt) : "-"}</p>
                <p><strong>Upload bis:</strong> {todayCalendar?.uploadUntil ? formatDateTime(todayCalendar.uploadUntil) : "-"}</p>
                <p><strong>Scheduler:</strong> {triggerRuntime?.runtime?.autoPaused ? "pausiert" : "aktiv"}</p>
                <p><strong>Lease Owner:</strong> {triggerRuntime?.runtime?.lease?.ownerId || "-"}</p>
              </div>
            </article>
            <div className="grid4">
            <CardStat title="Nutzer" value={stats.users} />
            <CardStat title="Geräte" value={stats.devices} />
            <CardStat title="Fotos" value={stats.photos} />
            <CardStat title="Prompt-Events" value={stats.prompts} />
            <CardStat title="Tage aktiv" value={stats.runningDays} />
            <CardStat title="Bilder gesamt" value={stats.totalImages} />
            <CardStat title="Speicher gesamt" value={formatBytes(stats.storageBytes)} />
            <CardStat
              title="Consent-Quote"
              value={`${Math.round((stats.diagnosticsConsentRate ?? 0) * 100)}% (${stats.diagnosticsConsentUsers ?? 0})`}
            />
            </div>
          </div>
        )}

        {activeTab === "system" && (
          <div className="stack">
            <div className="row">
              <h2>System Health</h2>
              <button onClick={() => loadSystemHealth(token)}>Aktualisieren</button>
            </div>
            {!systemHealth && <p>Keine Daten geladen.</p>}
            {systemHealth && (
              <>
                <article className="settings-current">
                  <div className="settings-grid">
                    <p><strong>Gesamtstatus:</strong> {systemHealth.ok ? "OK" : "DEGRADED"}</p>
                    <p><strong>Version:</strong> {systemHealth.version}</p>
                    <p><strong>Push Provider:</strong> {systemHealth.provider}</p>
                    <p><strong>Zeitpunkt:</strong> {formatDateTime(systemHealth.time)}</p>
                    <p><strong>Upload-Speicher:</strong> {formatBytes(systemHealth.uploadSizeBytes || 0)}</p>
                    <p><strong>Uptime:</strong> {formatDuration(systemHealth.metrics?.uptimeSec || 0)}</p>
                  </div>
                </article>

                <h3>Komponenten</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Komponente</th>
                      <th>Status</th>
                      <th>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {systemHealth.components.map((c) => (
                      <tr key={c.name}>
                        <td>{c.name}</td>
                        <td>{c.ok ? "OK" : "FEHLER"}</td>
                        <td>{c.message}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>

                <h3>API-Metriken</h3>
                <div className="grid4">
                  <CardStat title="Requests gesamt" value={Number(systemHealth.metrics?.requestsTotal || 0)} />
                  <CardStat title="Fehler gesamt" value={Number(systemHealth.metrics?.errorsTotal || 0)} />
                  <CardStat title="4xx" value={Number(systemHealth.metrics?.errors4xx || 0)} />
                  <CardStat title="5xx" value={Number(systemHealth.metrics?.errors5xx || 0)} />
                </div>
                <div className="grid4">
                  <CardStat title="Error-Rate %" value={Number(systemHealth.metrics?.errorRatePercent || 0)} />
                  <CardStat title="P95 ms" value={Number(systemHealth.metrics?.p95LatencyMs || 0)} />
                  <CardStat title="Recent Req" value={Number(systemHealth.metrics?.recentRequestsCnt || 0)} />
                  <CardStat title="Push Sent" value={Number(systemHealth.metrics?.push?.sent || 0)} />
                </div>
                <div className="grid4">
                  <CardStat title="Push Failed" value={Number(systemHealth.metrics?.push?.failed || 0)} />
                  <CardStat title="Push Invalid" value={Number(systemHealth.metrics?.push?.invalidTokens || 0)} />
                  <CardStat title="Push Errors" value={Number(systemHealth.metrics?.push?.errors || 0)} />
                </div>

                <h3>Letzter Moment</h3>
                <article className="settings-current">
                  <div className="settings-grid">
                    <p><strong>Tag:</strong> {systemHealth.latestPrompt?.day || "-"}</p>
                    <p><strong>Trigger:</strong> {systemHealth.latestPrompt?.triggeredAt ? formatDateTime(systemHealth.latestPrompt.triggeredAt) : "-"}</p>
                    <p><strong>Upload bis:</strong> {systemHealth.latestPrompt?.uploadUntil ? formatDateTime(systemHealth.latestPrompt.uploadUntil) : "-"}</p>
                    <p><strong>Quelle:</strong> {systemHealth.latestPrompt?.triggerSource || "-"}</p>
                    <p><strong>Angefordert von:</strong> {systemHealth.latestPrompt?.requestedByUser || "-"}</p>
                  </div>
                </article>
              </>
            )}
          </div>
        )}

        {activeTab === "events" && (
          <div className="stack">
            <button className="accent" onClick={onTriggerEvent}>Daily Event manuell auslösen</button>
            <button onClick={() => onTriggerEvent({ silent: true })}>Interner Daily-Test (ohne Push)</button>
            <button onClick={() => onTriggerEvent({ notifyUserIds: targetUserId ? [targetUserId] : [] })} disabled={!targetUserId}>
              Daily-Test nur für gewählten Benutzer (mit Push)
            </button>
            <button className="danger" onClick={onResetToday}>Heutigen Tag zurücksetzen</button>

            <label>
              Custom Nachricht an alle Geräte
              <input value={broadcastBody} onChange={(e) => setBroadcastBody(e.target.value)} />
            </label>
            <button onClick={onBroadcast}>Benachrichtigung senden</button>

            <label>
              Push nur an einzelnen Benutzer
              <input
                placeholder="Benutzer suchen..."
                value={targetUserSearch}
                onChange={(e) => setTargetUserSearch(e.target.value)}
              />
              <select value={targetUserId || ""} onChange={(e) => setTargetUserId(Number(e.target.value || 0))}>
                <option value="">Benutzer wählen</option>
                {filteredTargetUsers.map((u) => (
                  <option key={u.id} value={u.id}>{u.username} ({u.deviceCount} Geräte)</option>
                ))}
              </select>
            </label>
            <button onClick={onNotifySingleUser}>Nur diesen Benutzer benachrichtigen</button>

            <label>
              Update-Version für Hinweis
              <input value={updateNoticeVersion} onChange={(e) => setUpdateNoticeVersion(e.target.value)} />
            </label>
            <button onClick={onSendUpdateNotice}>Update-Hinweis senden</button>
          </div>
        )}

        {activeTab === "commands" && (
          <div className="stack">
            <article className="settings-current">
              <p>Alle Chat-Command-Einstellungen werden nur hier verwaltet. Im Tab Einstellungen sind keine Command-Optionen mehr.</p>
            </article>
            <div className="row">
              <h2>Command-Builder</h2>
              <button onClick={() => { setEditingCommandId(null); setCommandDraft(emptyCommandDraft); }}>Neuer Command</button>
            </div>

            <form onSubmit={onSaveCommand} className="stack">
              <label>
                Name
                <input value={commandDraft.name} onChange={(e) => setCommandDraft({ ...commandDraft, name: e.target.value })} required />
              </label>
              <label>
                Command (z. B. -moment)
                <input value={commandDraft.command} onChange={(e) => setCommandDraft({ ...commandDraft, command: e.target.value })} required />
              </label>
              <label>
                Aktion
                <select
                  value={commandDraft.action}
                  onChange={(e) => setCommandDraft({ ...commandDraft, action: e.target.value as CommandDraft["action"] })}
                >
                  <option value="trigger_moment">Moment ausloesen</option>
                  <option value="clear_chat">Chat leeren</option>
                  <option value="broadcast_push">Push an alle</option>
                  <option value="send_chat_message">Bot-Chatnachricht</option>
                </select>
              </label>
              <div className="row">
                <label className="checkbox">
                  <input type="checkbox" checked={commandDraft.enabled} onChange={(e) => setCommandDraft({ ...commandDraft, enabled: e.target.checked })} />
                  Aktiv
                </label>
                <label className="checkbox">
                  <input type="checkbox" checked={commandDraft.requireAdmin} onChange={(e) => setCommandDraft({ ...commandDraft, requireAdmin: e.target.checked })} />
                  Nur Admin
                </label>
              </div>
              <div className="row">
                <label className="checkbox">
                  <input type="checkbox" checked={commandDraft.sendPush} onChange={(e) => setCommandDraft({ ...commandDraft, sendPush: e.target.checked })} />
                  Push senden
                </label>
                <label className="checkbox">
                  <input type="checkbox" checked={commandDraft.postChat} onChange={(e) => setCommandDraft({ ...commandDraft, postChat: e.target.checked })} />
                  Chat-Meldung posten
                </label>
              </div>
              <label>
                Push-Text (Platzhalter: {"{user}"})
                <input value={commandDraft.pushText} onChange={(e) => setCommandDraft({ ...commandDraft, pushText: e.target.value })} />
              </label>
              <label>
                Chat-Text (Platzhalter: {"{user}"})
                <input value={commandDraft.responseText} onChange={(e) => setCommandDraft({ ...commandDraft, responseText: e.target.value })} />
              </label>
              <label>
                Cooldown (Sekunden)
                <input
                  type="number"
                  min={0}
                  value={commandDraft.cooldownSecond}
                  onChange={(e) => setCommandDraft({ ...commandDraft, cooldownSecond: Number(e.target.value) })}
                />
              </label>
              <div className="row">
                <button type="submit">{editingCommandId == null ? "Command erstellen" : "Command speichern"}</button>
                {editingCommandId != null && (
                  <button type="button" onClick={() => { setEditingCommandId(null); setCommandDraft(emptyCommandDraft); }}>
                    Bearbeitung abbrechen
                  </button>
                )}
              </div>
            </form>

            <h2>Vorhandene Commands</h2>
            {chatCommands.length === 0 && <p>Keine Commands angelegt.</p>}
            <table className="table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Command</th>
                  <th>Aktion</th>
                  <th>Status</th>
                  <th>Zuletzt genutzt</th>
                  <th>Aktionen</th>
                </tr>
              </thead>
              <tbody>
                {chatCommands.map((cmd) => (
                  <tr key={cmd.id}>
                    <td>{cmd.name}</td>
                    <td><code>{cmd.command}</code></td>
                    <td>{cmd.action}</td>
                    <td>{cmd.enabled ? "Aktiv" : "Aus"}</td>
                    <td>{cmd.lastUsedAt ? formatDateTime(cmd.lastUsedAt) : "-"}</td>
                    <td>
                      <div className="row">
                        <button onClick={() => onEditCommand(cmd)}>Bearbeiten</button>
                        <button className="danger" onClick={() => onDeleteCommand(cmd)}>Loeschen</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "users" && (
          <div className="stack">
            <h2>Neuen Nutzer anlegen</h2>
            <form onSubmit={onCreateUser} className="stack">
              <label>
                Username
                <input value={newUsername} onChange={(e) => setNewUsername(e.target.value)} required />
              </label>
              <label>
                Passwort
                <input type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required />
              </label>
              <label className="checkbox">
                <input type="checkbox" checked={newIsAdmin} onChange={(e) => setNewIsAdmin(e.target.checked)} />
                Admin-Rechte
              </label>
              <button type="submit">User erstellen</button>
            </form>

            <h2>Bestehende Nutzer</h2>
            <div className="row">
              <button onClick={onCopyAdminToken}>Admin-Token kopieren</button>
            </div>
            <table className="table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Eingeladen von</th>
                  <th>Registriert am</th>
                  <th>Rolle</th>
                  <th>Fotos</th>
                  <th>Geräte</th>
                  <th>Letzte App/Fehler</th>
                  <th>Token</th>
                  <th>Passwort ändern</th>
                  <th>Löschen</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td>{u.username}</td>
                    <td>{u.invitedBy || "Direkt angelegt"}</td>
                    <td>{formatDateTime(u.createdAt)}</td>
                    <td>
                      <select value={u.isAdmin ? "admin" : "user"} onChange={(e) => onToggleAdmin(u, e.target.value === "admin")}>
                        <option value="user">User</option>
                        <option value="admin">Admin</option>
                      </select>
                    </td>
                    <td>{u.photoCount}</td>
                    <td>
                      <div className="stack">
                        <strong>{u.deviceCount}</strong>
                        {u.deviceDetails && u.deviceDetails.length > 0 ? (
                          u.deviceDetails.map((device, idx) => (
                            <span key={`${u.id}-${idx}`} className="small">
                              {device.name} ({device.appVersion || "unknown"})
                            </span>
                          ))
                        ) : u.deviceNames && u.deviceNames.length > 0 ? (
                          <span className="small">{u.deviceNames.join(", ")}</span>
                        ) : (
                          <span className="small">Keine Geraetenamen gemeldet</span>
                        )}
                      </div>
                    </td>
                    <td>
                      <div className="stack">
                        <span className="small"><strong>App:</strong> {u.lastAppVersion || "-"}</span>
                        <span className="small"><strong>Fehler:</strong> {u.lastError ? truncateText(u.lastError, 80) : "-"}</span>
                        <span className="small"><strong>Fehlerzeit:</strong> {u.lastErrorAt ? formatDateTime(u.lastErrorAt) : "-"}</span>
                        <span className="small"><strong>Profil OK:</strong> {u.lastProfileOkAt ? formatDateTime(u.lastProfileOkAt) : "-"}</span>
                      </div>
                    </td>
                    <td>
                      <button onClick={() => onCopyUserToken(u)} disabled={issuingTokenForUserId === u.id}>
                        {issuingTokenForUserId === u.id ? "Erzeuge..." : "Token kopieren"}
                      </button>
                    </td>
                    <td>
                      <div className="row">
                        <input
                          type="password"
                          placeholder="Neues Passwort"
                          value={resetPassword[u.id] || ""}
                          onChange={(e) => setResetPassword((prev) => ({ ...prev, [u.id]: e.target.value }))}
                        />
                        <button onClick={() => onResetPassword(u)}>Setzen</button>
                      </div>
                    </td>
                    <td>
                      <button className="danger" onClick={() => onDeleteUser(u)}>Löschen</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "feed" && (
          <div className="stack">
            <div className="row">
              <label>
                Tag
                <input type="date" value={feedDay} onChange={(e) => setFeedDay(e.target.value)} />
              </label>
              <button onClick={() => loadFeed(token, feedDay)}>Feed laden</button>
            </div>
            {feedMonthRecap && (
              <article className="settings-current">
                <h3>Monatsrueckblick {feedMonthRecap.monthLabel}</h3>
                <p>Dein Monat in {feedMonthRecap.yourMoments} Momenten</p>
                {feedMonthRecap.mostReliableUser && (
                  <p>
                    <strong>Am zuverlaessigsten dabei:</strong> {feedMonthRecap.mostReliableUser.username} ({feedMonthRecap.mostReliableUser.count} Tage)
                  </p>
                )}
                {feedMonthRecap.topSpontaneous.length > 0 && (
                  <div>
                    <strong>Top 5 spontanste Momente</strong>
                    <ul>
                      {feedMonthRecap.topSpontaneous.slice(0, 5).map((row) => (
                        <li key={`${row.day}-${row.userId}-${row.createdAt}`}>
                          {new Date(`${row.day}T00:00:00`).toLocaleDateString()}: {row.username} nach {row.minutesAfterTrigger} min
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
              </article>
            )}
            {feedItems.length === 0 && <p>Keine Eintraege fuer diesen Tag.</p>}
            <div className="feed-grid">
              {feedItems.map((item) => (
                <article key={`${item.user.id}-${item.photo.id}`} className="feed-card">
                  <div className="row">
                    <strong>{item.user.username}</strong>
                    {item.isLate && <span className="late">Spaet</span>}
                  </div>
                  {(item.triggerSource === "chat_command" || item.triggerSource === "special_request") && item.requestedByUser && (
                    <p className="small"><strong>Sondermoment:</strong> von {item.requestedByUser} angefordert</p>
                  )}
                  <div className="photo-grid">
                    <a href={item.photo.url} target="_blank" rel="noreferrer">
                      <img src={item.photo.url} alt={`${item.user.username} back`} />
                    </a>
                    {item.photo.secondUrl && (
                      <a href={item.photo.secondUrl} target="_blank" rel="noreferrer">
                        <img src={item.photo.secondUrl} alt={`${item.user.username} front`} />
                      </a>
                    )}
                  </div>
                  {item.photo.caption && <p className="small">{item.photo.caption}</p>}
                </article>
              ))}
            </div>
          </div>
        )}

        {activeTab === "chat" && (
          <div className="stack">
            <div className="row">
              <h2>Chatverlauf</h2>
              <div className="row">
                <button onClick={() => loadChat(token)}>Aktualisieren</button>
                <button className="danger" onClick={onClearChat}>Chat leeren</button>
              </div>
            </div>
            <form onSubmit={onSendAdminChat} className="row">
              <input
                value={chatDraft}
                onChange={(e) => setChatDraft(e.target.value)}
                placeholder="Als Admin in den Chat schreiben..."
              />
              <button type="submit">Senden</button>
            </form>
            {chatItems.length === 0 && <p>Keine Chat-Nachrichten.</p>}
            <div className="chat-list">
              {chatItems.map((msg) => (
                <article key={msg.id} className="chat-item clean">
                  <div className="chat-head">
                    <strong className="chat-user">{msg.user.username}</strong>
                    <span className="small chat-time">{formatDateTime(msg.createdAt)}</span>
                  </div>
                  <p className="chat-body">{msg.body}</p>
                </article>
              ))}
            </div>
          </div>
        )}

        {activeTab === "calendar" && (
          <div className="stack">
            <div className="row">
              <h2>Naechste 7 Tage</h2>
              <button onClick={() => loadCalendar(token)}>Aktualisieren</button>
            </div>
            <table className="table">
              <thead>
                <tr>
                  <th>Tag</th>
                  <th>Geplant</th>
                  <th>Status</th>
                  <th>Quelle</th>
                  <th>Ausloeser</th>
                  <th>Aktion</th>
                </tr>
              </thead>
              <tbody>
                {calendarItems.map((item) => (
                  <tr key={item.day}>
                    <td>{new Date(`${item.day}T00:00:00`).toLocaleDateString()}</td>
                    <td>
                      <input
                        type="datetime-local"
                        value={calendarDrafts[item.day] || ""}
                        onChange={(e) => setCalendarDrafts((prev) => ({ ...prev, [item.day]: e.target.value }))}
                      />
                    </td>
                    <td>{item.triggeredAt ? "Ausgeloest" : "Geplant"}</td>
                    <td>{item.source === "manual" ? "Manuell" : "Auto"}</td>
                    <td>
                      {(item.triggerSource === "chat_command" || item.triggerSource === "special_request") && item.requestedByUser
                        ? `Sondermoment (${item.requestedByUser})`
                        : item.triggerSource === "admin_manual"
                          ? "Admin"
                          : item.triggerSource === "admin_reset"
                            ? "Admin Reset"
                            : "Scheduler"}
                    </td>
                    <td>
                      <button onClick={() => onSaveCalendarDay(item.day)}>Speichern</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "history" && (
          <div className="stack">
            <div className="row">
              <h2>Vergangene Daily-Tage</h2>
              <div className="row">
                <label>
                  Tage
                  <select value={historyDays} onChange={(e) => setHistoryDays(Number(e.target.value))}>
                    <option value={1}>Letzter Tag</option>
                    <option value={7}>7</option>
                    <option value={14}>14</option>
                    <option value={30}>30</option>
                    <option value={60}>60</option>
                  </select>
                </label>
                <label>
                  Offset
                  <input
                    type="number"
                    min={0}
                    value={historyOffset}
                    onChange={(e) => setHistoryOffset(Math.max(0, Number(e.target.value) || 0))}
                    style={{ width: 90 }}
                  />
                </label>
                <button onClick={() => loadHistory(token, historyDays, historyOffset)}>Aktualisieren</button>
              </div>
            </div>
            <div className="grid4">
              <CardStat title="Tage im Blick" value={historyItems.length} />
              <CardStat
                title="Tracking seit"
                value={historyTrackingSince ? new Date(`${historyTrackingSince}T00:00:00`).toLocaleDateString() : "-"}
              />
              <CardStat
                title="Ø Poster"
                value={historyItems.length > 0 ? (historyItems.reduce((acc, row) => acc + row.postedUsersCount, 0) / historyItems.length).toFixed(1) : "-"}
              />
              <CardStat
                title="Ø Daily-Poster"
                value={historyItems.length > 0 ? (historyItems.reduce((acc, row) => acc + row.dailyMomentUsersCount, 0) / historyItems.length).toFixed(1) : "-"}
              />
            </div>
            <p className="small">
              Online-Zeiten stammen aus serverseitigem Activity-Tracking. Tage vor dem Tracking-Rollout zeigen bewusst kein geschaetztes Online-Ergebnis.
            </p>
            <div className="history-chart-grid">
              <article className="history-chart-card">
                <h3>Trend: Online vs Posting</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <LineChart data={historyTrendChartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="dayLabel" />
                      <YAxis allowDecimals={false} />
                      <Tooltip />
                      <Legend />
                      <Line type="monotone" dataKey="onlineUsers" name="Online" stroke="#386dff" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={550} />
                      <Line type="monotone" dataKey="postedUsers" name="Poster" stroke="#18a188" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={550} />
                      <Line type="monotone" dataKey="dailyMomentUsers" name="Daily-Moment" stroke="#31bf62" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={550} />
                      <Line type="monotone" dataKey="extraUsers" name="Extras" stroke="#f0872f" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={550} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Composition: Daily / Extra / Capsule</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={historyCompositionChartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="dayLabel" />
                      <YAxis allowDecimals={false} />
                      <Tooltip />
                      <Legend />
                      <Bar dataKey="dailyMomentPhotos" name="Daily-Moment" stackId="a" fill="#33ba67" isAnimationActive={!reduceMotion} animationDuration={520} />
                      <Bar dataKey="extraPhotos" name="Extra" stackId="a" fill="#f0872f" isAnimationActive={!reduceMotion} animationDuration={520} />
                      <Bar dataKey="capsulePhotos" name="Capsule" stackId="a" fill="#7861d8" isAnimationActive={!reduceMotion} animationDuration={520} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Conversion-Funnel pro Tag</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <AreaChart data={historyConversionChartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="dayLabel" />
                      <YAxis allowDecimals={false} />
                      <Tooltip />
                      <Legend />
                      <Area type="monotone" dataKey="onlineUsers" name="Online" fill="#4f87ff" stroke="#386dff" fillOpacity={0.28} isAnimationActive={!reduceMotion} animationDuration={500} />
                      <Area type="monotone" dataKey="postedUsers" name="Gepostet" fill="#1cb39a" stroke="#18a188" fillOpacity={0.3} isAnimationActive={!reduceMotion} animationDuration={500} />
                      <Area type="monotone" dataKey="dailyMomentUsers" name="Daily-Moment" fill="#53c773" stroke="#32b85b" fillOpacity={0.34} isAnimationActive={!reduceMotion} animationDuration={500} />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Trigger Quality (Delay vs Poster)</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <ScatterChart>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="x" name="Trigger Delay (min)" />
                      <YAxis dataKey="y" name="Poster" allowDecimals={false} />
                      <Tooltip cursor={{ strokeDasharray: "4 4" }} formatter={(v) => v} />
                      <Scatter data={historyScatterData} fill="#3f79ff" isAnimationActive={!reduceMotion} animationDuration={500} />
                    </ScatterChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Photo-Mix (Anteil)</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <PieChart>
                      <Tooltip formatter={(value) => `${value}%`} />
                      <Legend />
                      <Pie data={historyPhotoMixPieData} dataKey="value" nameKey="name" outerRadius={84} innerRadius={45} isAnimationActive={!reduceMotion} animationDuration={520}>
                        {historyPhotoMixPieData.map((entry, index) => (
                          <Cell key={`${entry.name}-${index}`} fill={index === 0 ? "#33ba67" : index === 1 ? "#f0872f" : "#7861d8"} />
                        ))}
                      </Pie>
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Zuverlaessigste Nutzer</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={historyReliableChartData} layout="vertical">
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis type="number" domain={[0, 100]} />
                      <YAxis type="category" dataKey="username" width={90} />
                      <Tooltip formatter={(value) => `${value}%`} />
                      <Bar dataKey="scorePercent" name="Reliability %" fill="#2cb280" isAnimationActive={!reduceMotion} animationDuration={520} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Extra-Lastige Nutzer</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={historyExtraHeavyChartData} layout="vertical">
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis type="number" domain={[0, 100]} />
                      <YAxis type="category" dataKey="username" width={90} />
                      <Tooltip formatter={(value) => `${value}%`} />
                      <Bar dataKey="scorePercent" name="Extra %" fill="#f08a37" isAnimationActive={!reduceMotion} animationDuration={520} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Anomalie-Timeline</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <ScatterChart>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="dayLabel" name="Tag" />
                      <YAxis dataKey="severityScore" name="Severity" domain={[0, 3]} ticks={[1, 2, 3]} />
                      <Tooltip formatter={(value) => (Number(value) === 3 ? "high" : Number(value) === 2 ? "medium" : "low")} />
                      <Scatter data={historyAnomalyTimelineData} isAnimationActive={!reduceMotion} animationDuration={500}>
                        {historyAnomalyTimelineData.map((row, idx) => (
                          <Cell key={`${row.day}-${idx}`} fill={row.severity === "high" ? "#de5151" : row.severity === "medium" ? "#e6a13f" : "#51a6df"} />
                        ))}
                      </Scatter>
                    </ScatterChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Cohort Trend (7d vs 30d)</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={historyCohortTrendData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="username" />
                      <YAxis domain={[0, 100]} />
                      <Tooltip formatter={(value) => `${value}%`} />
                      <Legend />
                      <Bar dataKey="participation7d" name="7 Tage" fill="#3f76ff" isAnimationActive={!reduceMotion} animationDuration={520} />
                      <Bar dataKey="participation30d" name="30 Tage" fill="#28b890" isAnimationActive={!reduceMotion} animationDuration={520} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </article>
            </div>
            <div className="grid4">
              <article className="stat">
                <h3>Zuverlaessig (Top 5)</h3>
                <p>{historyReliableTop.length}</p>
              </article>
              <article className="stat">
                <h3>Extra-lastig (Top 5)</h3>
                <p>{historyExtraHeavyTop.length}</p>
              </article>
              <article className="stat">
                <h3>Warnungen</h3>
                <p>{historyAnomalies.length}</p>
              </article>
              <article className="stat">
                <h3>Fehlerindikatoren</h3>
                <p>{historyItems.reduce((acc, row) => acc + Number(row.debugErrorCount || 0), 0)}</p>
              </article>
            </div>
            <div className="grid4">
              <CardStat title="On-Time Trigger" value={`${Math.round(historyReliability.onTimeTriggerRate * 100)}%`} />
              <CardStat title="Avg Trigger Delay" value={`${historyReliability.avgAbsoluteTriggerDelayMinutes.toFixed(1)} min`} />
              <CardStat title="Avg Aktivitaet/Online" value={historyReliability.avgRequestsPerOnlineUser.toFixed(2)} />
              <CardStat title="Error Rate/Tag" value={historyReliability.errorIndicatorRatePerDay.toFixed(2)} />
            </div>
            {historyAnomalies.length > 0 && (
              <div className="stack">
                <h3>Warnungen</h3>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Tag</th>
                      <th>Schwere</th>
                      <th>Grund</th>
                      <th>Details</th>
                    </tr>
                  </thead>
                  <tbody>
                    {historyAnomalies.map((row, idx) => (
                      <tr key={`${row.day}-${row.reason}-${idx}`}>
                        <td>{new Date(`${row.day}T00:00:00`).toLocaleDateString()}</td>
                        <td>{row.severity}</td>
                        <td>{row.reason}</td>
                        <td>{row.details || "-"}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {(historyReliableTop.length > 0 || historyExtraHeavyTop.length > 0) && (
              <div className="grid2">
                <div className="stack">
                  <h3>Zuverlaessig</h3>
                  {historyReliableTop.length === 0 ? (
                    <p className="small">Keine Daten im Zeitraum.</p>
                  ) : (
                    <table className="table">
                      <thead>
                        <tr>
                          <th>Nutzer</th>
                          <th>Prompt-Tage</th>
                          <th>Post-Tage</th>
                          <th>Score</th>
                          <th>Trend (7d/30d)</th>
                        </tr>
                      </thead>
                      <tbody>
                        {historyReliableTop.map((row) => (
                          <tr key={`reliable-${row.userId}`}>
                            <td>@{row.username}</td>
                            <td>{row.promptDays}</td>
                            <td>{row.postedDays}</td>
                            <td>{typeof row.reliabilityScore === "number" ? `${Math.round(row.reliabilityScore * 100)}%` : "-"}</td>
                            <td>
                              {typeof row.participation7d === "number" && typeof row.participation30d === "number"
                                ? `${Math.round(row.participation7d * 100)}% / ${Math.round(row.participation30d * 100)}%`
                                : "-"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
                <div className="stack">
                  <h3>Extra-lastig</h3>
                  {historyExtraHeavyTop.length === 0 ? (
                    <p className="small">Keine Daten im Zeitraum.</p>
                  ) : (
                    <table className="table">
                      <thead>
                        <tr>
                          <th>Nutzer</th>
                          <th>Extra-Tage</th>
                          <th>Post-Tage</th>
                          <th>Extra-Score</th>
                        </tr>
                      </thead>
                      <tbody>
                        {historyExtraHeavyTop.map((row) => (
                          <tr key={`extra-heavy-${row.userId}`}>
                            <td>@{row.username}</td>
                            <td>{row.extraDays}</td>
                            <td>{row.postedDays}</td>
                            <td>{typeof row.extraBiasScore === "number" ? `${Math.round(row.extraBiasScore * 100)}%` : "-"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            )}
            {historyItems.length === 0 && <p>Keine Historie vorhanden.</p>}
            {historyItems.length > 0 && (
              <table className="table">
                <thead>
                  <tr>
                    <th>Tag</th>
                    <th>Geplant</th>
                    <th>Ausgeloest</th>
                    <th>Quelle</th>
                    <th>Online</th>
                    <th>Poster</th>
                    <th>Daily</th>
                    <th>Extras</th>
                    <th>Fotos</th>
                    <th>Details</th>
                  </tr>
                </thead>
                <tbody>
                  {historyItems.flatMap((item) => {
                    const expanded = !!expandedHistoryDays[item.day];
                    const userRows = item.userActivity ?? [];
                    const hasDetails = !!item.analytics || userRows.length > 0 || !item.onlineTrackingAvailable || item.commentCount > 0 || item.reactionCount > 0 || item.chatMessageCount > 0 || item.timeCapsuleCount > 0;
                    const sourceLabel =
                      (item.triggerSource === "chat_command" || item.triggerSource === "special_request") && item.requestedByUser
                        ? `Sondermoment (${item.requestedByUser})`
                        : item.triggerSource === "admin_manual"
                          ? "Admin"
                          : item.triggerSource === "admin_reset"
                            ? "Admin Reset"
                            : item.source === "manual"
                              ? "Manuell"
                              : "Scheduler";
                    const rows = [
                      <tr key={item.day}>
                        <td>{new Date(`${item.day}T00:00:00`).toLocaleDateString()}</td>
                        <td>{item.plannedAt ? formatDateTime(item.plannedAt) : "-"}</td>
                        <td>{item.triggeredAt ? formatDateTime(item.triggeredAt) : "-"}</td>
                        <td>{sourceLabel}</td>
                        <td>{item.onlineTrackingAvailable ? Number(item.onlineUsersCount || 0) : "-"}</td>
                        <td>{item.postedUsersCount}</td>
                        <td>{item.dailyMomentUsersCount}</td>
                        <td>{item.extraUsersCount}</td>
                        <td>{item.photoCount}</td>
                        <td>
                          {hasDetails ? (
                            <button
                              onClick={() => setExpandedHistoryDays((prev) => ({ ...prev, [item.day]: !expanded }))}
                            >
                              {expanded ? "Weniger" : "Mehr"}
                            </button>
                          ) : (
                            <span className="small">-</span>
                          )}
                        </td>
                      </tr>,
                    ];
                    if (expanded) {
                      rows.push(
                        <tr key={`${item.day}-details`}>
                          <td colSpan={10}>
                            <div className="stack">
                              <div className="grid4">
                                <CardStat title="Kommentare" value={item.commentCount} />
                                <CardStat title="Reaktionen" value={item.reactionCount} />
                                <CardStat title="Chat" value={item.chatMessageCount} />
                                <CardStat title="Capsules" value={`${item.timeCapsuleCount} / privat ${item.privateCapsuleCount}`} />
                              </div>
                              {item.analytics && (
                                <div className="grid4">
                                  <CardStat title="Prompt/Fotos" value={`${Math.round(item.analytics.promptPhotoRatio * 100)}%`} />
                                  <CardStat title="Extra/Fotos" value={`${Math.round(item.analytics.extraPhotoRatio * 100)}%`} />
                                  <CardStat title="Aktivitaet/Online" value={item.analytics.avgRequestsPerOnline.toFixed(1)} />
                                  <CardStat
                                    title="Trigger Delay"
                                    value={item.analytics.hasTriggerPerformance ? `${item.analytics.triggerDelayMinutes} min` : "-"}
                                  />
                                </div>
                              )}
                              {!item.onlineTrackingAvailable && (
                                <p className="small">Exakte Online-Zeiten sind fuer diesen Tag noch nicht verfuegbar.</p>
                              )}
                              {userRows.length === 0 ? (
                                <p className="small">Keine Nutzeraktivitaet fuer diesen Tag gespeichert.</p>
                              ) : (
                                <table className="table">
                                  <thead>
                                    <tr>
                                      <th>Nutzer</th>
                                      <th>Erstes Online</th>
                                      <th>Letztes Online</th>
                                      <th>Requests</th>
                                      <th>Status</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {userRows.map((userRow) => (
                                      <tr key={`${item.day}-${userRow.userId}`}>
                                        <td>@{userRow.username}</td>
                                        <td>{userRow.firstSeenAt ? formatDateTime(userRow.firstSeenAt) : "-"}</td>
                                        <td>{userRow.lastSeenAt ? formatDateTime(userRow.lastSeenAt) : "-"}</td>
                                        <td>{userRow.requestCount}</td>
                                        <td>
                                          {userRow.postedPrompt ? "Prompt" : userRow.postedExtra ? "Extra" : userRow.posted ? "Post" : "kein Post"}
                                        </td>
                                      </tr>
                                    ))}
                                  </tbody>
                                </table>
                              )}
                            </div>
                          </td>
                        </tr>,
                      );
                    }
                    return rows;
                  })}
                </tbody>
              </table>
            )}
          </div>
        )}

        {activeTab === "performance" && (
          <div className="stack">
            {Number(triggerAuditSummary?.summary?.duplicateAttempts || 0) > 0 && (
              <article className="history-chart-card" style={{ borderColor: "#c74444" }}>
                <h3 style={{ color: "#ff6f6f" }}>Mehrfach-Trigger Alarm</h3>
                <p className="small">
                  {triggerAuditSummary?.summary?.duplicateAttempts} zusaetzliche Trigger-Versuche erkannt.
                  Bitte Trigger Audit pruefen.
                </p>
                <div className="row">
                  <button onClick={() => navigateTab("trigger_audit")}>Trigger Audit oeffnen</button>
                  <button onClick={() => openIncidentExportWithRecentWindow(60)}>Incident-Export</button>
                </div>
              </article>
            )}
            <div className="row">
              <h2>Performance</h2>
              <div className="row">
                <label>
                  Von
                  <input type="datetime-local" value={performanceFrom} onChange={(e) => setPerformanceFrom(e.target.value)} />
                </label>
                <label>
                  Bis
                  <input type="datetime-local" value={performanceTo} onChange={(e) => setPerformanceTo(e.target.value)} />
                </label>
                <label>
                  Bucket
                  <select value={performanceBucket} onChange={(e) => setPerformanceBucket(e.target.value as "1m" | "5m")}>
                    <option value="1m">1 Minute</option>
                    <option value="5m">5 Minuten</option>
                  </select>
                </label>
                <button onClick={() => loadPerformance(token, performanceBucket, performanceFrom, performanceTo)}>Aktualisieren</button>
                <button onClick={() => onDownloadPerformance("json")}>JSON Export</button>
                <button onClick={() => onDownloadPerformance("csv")}>CSV Export</button>
              </div>
            </div>

            <article className="settings-current">
              <h3>Daily-Moment Last-Tracking</h3>
              <div className="settings-grid">
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={performanceTrackingEnabled}
                    onChange={(e) => setPerformanceTrackingEnabled(e.target.checked)}
                  />
                  Tracking bei Daily-Trigger aktivieren
                </label>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={performanceTrackingOneShot}
                    onChange={(e) => setPerformanceTrackingOneShot(e.target.checked)}
                    disabled={!performanceTrackingEnabled}
                  />
                  Nur einmal (auto aus nach erstem Daily)
                </label>
                <label>
                  Tracking-Fenster (Minuten)
                  <input
                    type="number"
                    min={5}
                    max={180}
                    value={performanceTrackingWindowMinutes}
                    onChange={(e) => setPerformanceTrackingWindowMinutes(Number(e.target.value))}
                  />
                </label>
              </div>
              <div className="row">
                <button onClick={onSavePerformanceTracking}>Tracking speichern</button>
                <button onClick={onDownloadTrackingExport}>Daily-Tracking JSON exportieren</button>
              </div>
              <p className="small">
                Aktiv: {performanceTrackingActiveSpike ? `${formatDateTime(performanceTrackingActiveSpike.windowStart)} bis ${formatDateTime(performanceTrackingActiveSpike.windowEnd)}` : "nein"}.
                Letztes Event: {performanceTrackingLatestSpike ? `${formatDateTime(performanceTrackingLatestSpike.triggerAt)} (${Number(performanceTrackingLatestSpike.p95PeakMs || 0).toFixed(1)} ms)` : "-"}.
                Modus: {performanceTrackingEnabled ? (performanceTrackingOneShot ? "One-shot armed" : "dauerhaft aktiv") : "aus"}.
              </p>
            </article>

            <div className="grid4">
              <CardStat title="Requests" value={Number(performanceOverview?.summary?.requests || 0)} />
              <CardStat title="Errors" value={Number(performanceOverview?.summary?.errors || 0)} />
              <CardStat title="P95 Peak" value={`${Number(performanceOverview?.summary?.p95Peak || 0).toFixed(1)} ms`} />
              <CardStat title="P99 Peak" value={`${Number(performanceOverview?.summary?.p99Peak || 0).toFixed(1)} ms`} />
            </div>
            <div className="grid4">
              <CardStat title="Throttle Events" value={Number(performanceOverview?.summary?.throttleCount || 0)} />
              <CardStat title="Throttle Rate" value={`${(Number(performanceOverview?.summary?.throttleRate || 0) * 100).toFixed(2)}%`} />
              <CardStat title="Schema" value={performanceOverview?.schemaVersion || "1.0"} />
              <CardStat title="Error Classes" value={Number((performanceOverview?.errorClasses || []).length)} />
            </div>
            <div className="grid4">
              <CardStat title="SLO Status" value={performanceSlo?.status === "breach" ? "Breach" : "OK"} />
              <CardStat title="Feed P95" value={`${Number(performanceSlo?.metrics?.feedP95PeakMs || 0).toFixed(1)} ms`} />
              <CardStat title="5xx Rate" value={`${(Number(performanceSlo?.metrics?.global5xxRate || 0) * 100).toFixed(2)}%`} />
              <CardStat title="Upload Error-Rate" value={`${(Number(performanceSlo?.metrics?.uploadErrorRate || 0) * 100).toFixed(2)}%`} />
            </div>

            {performanceSlo && performanceSlo.violations?.length > 0 && (
              <article className="history-chart-card">
                <h3>SLO-Verletzungen ({performanceSlo.windowMinutes}m)</h3>
                <div className="table-wrap">
                  <table className="table">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Severity</th>
                        <th>Observed</th>
                        <th>Threshold</th>
                      </tr>
                    </thead>
                    <tbody>
                      {performanceSlo.violations.map((v) => (
                        <tr key={v.id}>
                          <td><code>{v.id}</code></td>
                          <td>{v.severity}</td>
                          <td>{v.unit === "ratio" ? `${(v.observed * 100).toFixed(2)}%` : `${Number(v.observed).toFixed(1)} ms`}</td>
                          <td>{v.unit === "ratio" ? `${(v.threshold * 100).toFixed(2)}%` : `${Number(v.threshold).toFixed(1)} ms`}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </article>
            )}

            <div className="history-chart-grid">
              <article className="history-chart-card">
                <h3>Requests & Errors</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <LineChart data={performanceTrendData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="bucketLabel" />
                      <YAxis allowDecimals={false} />
                      <Tooltip />
                      <Legend />
                      <Line type="monotone" dataKey="requests" name="Requests" stroke="#386dff" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={520} />
                      <Line type="monotone" dataKey="errors" name="Errors" stroke="#df5656" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={520} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Latency (P95 / P99)</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <AreaChart data={performanceTrendData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="bucketLabel" />
                      <YAxis />
                      <Tooltip />
                      <Legend />
                      <Area type="monotone" dataKey="p95Ms" name="P95 ms" fill="#3f79ff" stroke="#2c65f5" fillOpacity={0.24} isAnimationActive={!reduceMotion} animationDuration={500} />
                      <Area type="monotone" dataKey="p99Ms" name="P99 ms" fill="#aa5dff" stroke="#8f44e7" fillOpacity={0.24} isAnimationActive={!reduceMotion} animationDuration={500} />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>Daily-Spike Verlauf</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <BarChart data={performanceSpikeChartData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="dayLabel" />
                      <YAxis />
                      <Tooltip />
                      <Legend />
                      <Bar dataKey="feedReadCount" name="Feed Reads" fill="#3d7bff" isAnimationActive={!reduceMotion} animationDuration={500} />
                      <Bar dataKey="uploadCount" name="Uploads" fill="#2abf88" isAnimationActive={!reduceMotion} animationDuration={500} />
                      <Bar dataKey="errorCount" name="Errors" fill="#d85a5a" isAnimationActive={!reduceMotion} animationDuration={500} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              </article>
              <article className="history-chart-card">
                <h3>System: Memory & DB-Wait</h3>
                <div className="history-chart-wrap">
                  <ResponsiveContainer width="100%" height={260}>
                    <LineChart data={performanceSystemData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(120,140,190,0.25)" />
                      <XAxis dataKey="bucketLabel" />
                      <YAxis />
                      <Tooltip />
                      <Legend />
                      <Line type="monotone" dataKey="memAllocMb" name="Mem Alloc (MB)" stroke="#2abf88" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={520} />
                      <Line type="monotone" dataKey="dbWaitMs" name="DB Wait (ms)" stroke="#f5a524" strokeWidth={2} dot={false} isAnimationActive={!reduceMotion} animationDuration={520} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </article>
            </div>

            <h3>Route-Hotspots</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>Route</th>
                  <th>Methode</th>
                  <th>Requests</th>
                  <th>Error-Rate</th>
                  <th>P95 Peak</th>
                  <th>P99 Peak</th>
                </tr>
              </thead>
              <tbody>
                {performanceRoutes.length === 0 ? (
                  <tr>
                    <td colSpan={6}>Keine Daten.</td>
                  </tr>
                ) : (
                  performanceRoutes.map((row, idx) => (
                    <tr key={`${row.method}-${row.route}-${idx}`}>
                      <td>{row.route}</td>
                      <td>{row.method}</td>
                      <td>{row.requests}</td>
                      <td>{(Number(row.errorRate || 0) * 100).toFixed(2)}%</td>
                      <td>{Number(row.p95PeakMs || 0).toFixed(1)} ms</td>
                      <td>{Number(row.p99PeakMs || 0).toFixed(1)} ms</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>

            <h3>Error-Class Mix</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>Error Class</th>
                  <th>Count</th>
                  <th>Ratio</th>
                </tr>
              </thead>
              <tbody>
                {(performanceOverview?.errorClasses || []).length === 0 ? (
                  <tr>
                    <td colSpan={3}>Keine Error-Class Daten.</td>
                  </tr>
                ) : (
                  (performanceOverview?.errorClasses || []).map((row, idx) => (
                    <tr key={`${row.errorClass}-${idx}`}>
                      <td><code>{row.errorClass}</code></td>
                      <td>{row.count}</td>
                      <td>{(Number(row.ratio || 0) * 100).toFixed(2)}%</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>

            <h3>Spike-Events</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>Tag</th>
                  <th>Trigger</th>
                  <th>Push</th>
                  <th>Uploads</th>
                  <th>Feed Reads</th>
                  <th>Errors</th>
                  <th>P95 Peak</th>
                </tr>
              </thead>
              <tbody>
                {performanceSpikes.length === 0 ? (
                  <tr>
                    <td colSpan={7}>Keine Spike-Ereignisse.</td>
                  </tr>
                ) : (
                  performanceSpikes.map((row) => (
                    <tr key={row.id}>
                      <td>{new Date(`${row.day}T00:00:00`).toLocaleDateString()}</td>
                      <td>{formatDateTime(row.triggerAt)}</td>
                      <td>{row.pushSent}</td>
                      <td>{row.uploadCount}</td>
                      <td>{row.feedReadCount}</td>
                      <td>{row.errorCount}</td>
                      <td>{Number(row.p95PeakMs || 0).toFixed(1)} ms</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>

            <h3>DB-Hotspots</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>Route</th>
                  <th>Query Group</th>
                  <th>Count</th>
                  <th>P95 Peak</th>
                  <th>P99 Peak</th>
                  <th>Max Peak</th>
                </tr>
              </thead>
              <tbody>
                {(performanceOverview?.dbHotspots || []).length === 0 ? (
                  <tr>
                    <td colSpan={6}>Keine DB-Hotspots.</td>
                  </tr>
                ) : (
                  (performanceOverview?.dbHotspots || []).map((row, idx) => (
                    <tr key={`${row.route}-${row.queryGroup}-${idx}`}>
                      <td>{row.route}</td>
                      <td><code>{row.queryGroup}</code></td>
                      <td>{row.count}</td>
                      <td>{Number(row.p95PeakMs || 0).toFixed(1)} ms</td>
                      <td>{Number(row.p99PeakMs || 0).toFixed(1)} ms</td>
                      <td>{Number(row.maxPeakMs || 0).toFixed(1)} ms</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "incident_export" && (
          <div className="stack">
            <div className="row">
              <h2>Incident Export</h2>
              <div className="row">
                <button onClick={() => loadIncidentStatus(token)}>Status aktualisieren</button>
                <button onClick={onDownloadIncidentBundle}>Incident JSON herunterladen</button>
              </div>
            </div>
            <article className="settings-current">
              <h3>Forensik-Zeitfenster</h3>
              <div className="settings-grid">
                <label>
                  Von
                  <input type="datetime-local" value={incidentFrom} onChange={(e) => setIncidentFrom(e.target.value)} />
                </label>
                <label>
                  Bis
                  <input type="datetime-local" value={incidentTo} onChange={(e) => setIncidentTo(e.target.value)} />
                </label>
                <label>
                  Tagesfokus (optional)
                  <input type="date" value={incidentDay} onChange={(e) => setIncidentDay(e.target.value)} />
                </label>
                <label className="checkbox">
                  <input
                    type="checkbox"
                    checked={incidentIncludeGateway}
                    onChange={(e) => setIncidentIncludeGateway(e.target.checked)}
                  />
                  Gateway-Logs einbeziehen (wenn gemountet)
                </label>
              </div>
            </article>

            <div className="grid4">
              <CardStat title="Duplicate Attempts" value={Number(incidentStatus?.status?.duplicateAttempts || 0)} />
              <CardStat title="Mehrfach-Tage" value={Number(incidentStatus?.status?.multipleAttemptDays || 0)} />
              <CardStat title="Letzte Trigger-Quelle" value={incidentStatus?.status?.lastTriggerSource || "-"} />
              <CardStat title="Gateway verfügbar" value={incidentStatus?.status?.gatewayLogAvailable ? "ja" : "nein"} />
            </div>
            <div className="grid4">
              <CardStat title="Backend-Log verfügbar" value={incidentStatus?.status?.backendLogAvailable ? "ja" : "nein"} />
              <CardStat title="Schema" value={incidentStatus?.meta?.schemaVersion || "-"} />
              <CardStat title="Zeitraum von" value={incidentStatus?.meta?.from ? formatDateTime(incidentStatus.meta.from) : "-"} />
              <CardStat title="Zeitraum bis" value={incidentStatus?.meta?.to ? formatDateTime(incidentStatus.meta.to) : "-"} />
            </div>
            <article className="settings-current">
              <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
                <h3>Trigger Runtime</h3>
                <div className="row">
                  <label>
                    Fenster (Min)
                    <input
                      type="number"
                      min={5}
                      max={240}
                      value={triggerRuntimeWindowMinutes}
                      onChange={(e) =>
                        setTriggerRuntimeWindowMinutes(Math.max(5, Math.min(240, Number(e.target.value) || 60)))
                      }
                    />
                  </label>
                  <button onClick={() => loadTriggerRuntime(token)}>Runtime aktualisieren</button>
                  <button className="danger" onClick={() => onUpdateTriggerRuntime("pause", "manual_admin_pause")}>
                    Scheduler pausieren
                  </button>
                  <button onClick={() => onUpdateTriggerRuntime("unpause")}>Scheduler fortsetzen</button>
                  <button className="danger" onClick={() => onUpdateTriggerRuntime("release_lease")}>
                    Lease freigeben
                  </button>
                </div>
              </div>
              <div className="grid4">
                <CardStat title="Auto-Pause" value={triggerRuntime?.runtime?.autoPaused ? "aktiv" : "aus"} />
                <CardStat title="Pause-Grund" value={triggerRuntime?.runtime?.autoPauseReason || "-"} />
                <CardStat title="Lease Owner" value={triggerRuntime?.runtime?.lease?.ownerId || "-"} />
                <CardStat title="Ist Owner?" value={triggerRuntime?.runtime?.lease?.isOwner ? "ja" : "nein"} />
              </div>
              <div className="grid4">
                <CardStat title="Attempts (Fenster)" value={Number(triggerRuntime?.recent?.attempts || 0)} />
                <CardStat title="Blocked" value={Number(triggerRuntime?.recent?.blocked || 0)} />
                <CardStat title="Failed" value={Number(triggerRuntime?.recent?.failed || 0)} />
                <CardStat title="DB-Lock" value={Number(triggerRuntime?.recent?.dbLocked || 0)} />
              </div>
              <div className="grid4">
                <CardStat title="Duplikate heute" value={Number(triggerRuntime?.recent?.duplicateToday || 0)} />
                <CardStat title="Block-Rate" value={`${(Number(triggerRuntime?.recent?.blockRate || 0) * 100).toFixed(2)}%`} />
                <CardStat title="Lease Expired?" value={triggerRuntime?.runtime?.lease?.isExpired ? "ja" : "nein"} />
                <CardStat title="Lease bis" value={triggerRuntime?.runtime?.lease?.expiresAt ? formatDateTime(triggerRuntime.runtime.lease.expiresAt) : "-"} />
              </div>
              <div className="grid4">
                <CardStat title="Trigger SLO" value={triggerRuntime?.slo?.status === "breach" ? "BREACH" : "OK"} />
                <CardStat title="Lease-Contention" value={Number(triggerRuntime?.recent?.byReason?.not_lease_owner || 0)} />
                <CardStat title="Race Lost" value={Number(triggerRuntime?.recent?.byReason?.race_lost || 0)} />
                <CardStat title="Already Triggered" value={Number(triggerRuntime?.recent?.byReason?.already_triggered_today || 0)} />
              </div>
              {(triggerRuntime?.slo?.violations || []).length > 0 && (
                <article className="history-chart-card" style={{ borderColor: "#c74444" }}>
                  <h3 style={{ color: "#ff6f6f" }}>Trigger-SLO verletzt</h3>
                  <ul>
                    {(triggerRuntime?.slo?.violations || []).map((v, idx) => (
                      <li key={`${v.id}-${idx}`}>
                        <code>{v.id}</code>: observed={Number(v.observed).toFixed(3)} threshold={Number(v.threshold).toFixed(3)} ({v.unit})
                      </li>
                    ))}
                  </ul>
                </article>
              )}
              <p className="small">
                Last Tick: {triggerRuntime?.runtime?.lastTickAt ? formatDateTime(triggerRuntime.runtime.lastTickAt) : "-"} | Ergebnis:{" "}
                <code>{triggerRuntime?.runtime?.lastTickResult || "-"}</code> | Dispatch:{" "}
                <code>{triggerRuntime?.dispatch?.last?.status || "-"}</code>
              </p>
            </article>
            {(incidentStatus?.collectionWarnings || []).length > 0 && (
              <article className="history-chart-card">
                <h3>Collection Warnings</h3>
                <ul>
                  {(incidentStatus?.collectionWarnings || []).map((warning, idx) => (
                    <li key={`${warning}-${idx}`}>{warning}</li>
                  ))}
                </ul>
              </article>
            )}
            <p className="small">
              Der Export erzeugt ein einzelnes JSON-Bundle mit Trigger-Audit, Performance, History-Slice, Live-Snapshot und optionalen Log-Ausschnitten.
            </p>
          </div>
        )}

        {activeTab === "trigger_audit" && (
          <div className="stack">
            <div className="row">
              <h2>Trigger Audit</h2>
              <div className="row">
                <button onClick={() => loadTriggerAudit(token)}>Aktualisieren</button>
                <button onClick={() => onDownloadTriggerAudit("json")}>JSON Export</button>
                <button onClick={() => onDownloadTriggerAudit("csv")}>CSV Export</button>
              </div>
            </div>
            <article className="settings-current">
              <div className="settings-grid">
                <label>
                  Summary Zeitraum
                  <select value={triggerAuditDays} onChange={(e) => setTriggerAuditDays(Number(e.target.value))}>
                    <option value={1}>1 Tag</option>
                    <option value={7}>7 Tage</option>
                    <option value={14}>14 Tage</option>
                    <option value={30}>30 Tage</option>
                  </select>
                </label>
                <label>
                  Tag (optional)
                  <input type="date" value={triggerAuditDay} onChange={(e) => setTriggerAuditDay(e.target.value)} />
                </label>
                <label>
                  Quelle
                  <select value={triggerAuditSource} onChange={(e) => setTriggerAuditSource(e.target.value)}>
                    <option value="">Alle</option>
                    <option value="scheduler">scheduler</option>
                    <option value="admin_manual">admin_manual</option>
                    <option value="admin_manual_targeted">admin_manual_targeted</option>
                    <option value="admin_manual_silent">admin_manual_silent</option>
                    <option value="chat_command">chat_command</option>
                    <option value="special_request">special_request</option>
                    <option value="admin_reset">admin_reset</option>
                  </select>
                </label>
                <label>
                  Ergebnis
                  <select value={triggerAuditResult} onChange={(e) => setTriggerAuditResult(e.target.value)}>
                    <option value="">Alle</option>
                    <option value="triggered">triggered</option>
                    <option value="blocked">blocked</option>
                    <option value="failed">failed</option>
                  </select>
                </label>
                <label>
                  User-ID (optional)
                  <input
                    type="number"
                    min={0}
                    value={triggerAuditActorUserId}
                    onChange={(e) => setTriggerAuditActorUserId(Number(e.target.value) || 0)}
                  />
                </label>
                <label>
                  Request-ID (optional)
                  <input value={triggerAuditRequestId} onChange={(e) => setTriggerAuditRequestId(e.target.value)} />
                </label>
                <label>
                  Limit
                  <input
                    type="number"
                    min={1}
                    max={2000}
                    value={triggerAuditLimit}
                    onChange={(e) => setTriggerAuditLimit(Math.max(1, Math.min(2000, Number(e.target.value) || 200)))}
                  />
                </label>
              </div>
            </article>

            <div className="grid4">
              <CardStat title="Attempts" value={Number(triggerAuditSummary?.summary?.attempts || 0)} />
              <CardStat title="Triggered" value={Number(triggerAuditSummary?.summary?.triggered || 0)} />
              <CardStat title="Blocked" value={Number(triggerAuditSummary?.summary?.blocked || 0)} />
              <CardStat title="Failed" value={Number(triggerAuditSummary?.summary?.failed || 0)} />
            </div>
            <div className="grid4">
              <CardStat title="Duplicate Attempts" value={Number(triggerAuditSummary?.summary?.duplicateAttempts || 0)} />
              <CardStat title="Mehrfach-Tage" value={Number(triggerAuditSummary?.summary?.multipleAttemptDays || 0)} />
              <CardStat title="DB-Lock" value={Number(triggerAuditSummary?.summary?.dbLocked || 0)} />
              <CardStat title="Blocked Rate" value={`${(Number(triggerAuditSummary?.summary?.blockedRate || 0) * 100).toFixed(2)}%`} />
            </div>

            <h3>Timeline</h3>
            <table className="table">
              <thead>
                <tr>
                  <th>Zeit</th>
                  <th>Tag</th>
                  <th>Quelle</th>
                  <th>Attempt</th>
                  <th>Ergebnis</th>
                  <th>Reason</th>
                  <th>Nutzer</th>
                  <th>Request-ID</th>
                    <th>Before -&gt; After</th>
                </tr>
              </thead>
              <tbody>
                {triggerAuditItems.length === 0 ? (
                  <tr>
                    <td colSpan={9}>Keine Trigger-Audit-Eintraege.</td>
                  </tr>
                ) : (
                  triggerAuditItems.map((row) => (
                    <tr key={row.id}>
                      <td>{formatDateTime(row.occurredAt)}</td>
                      <td>{new Date(`${row.day}T00:00:00`).toLocaleDateString()}</td>
                      <td><code>{row.source || "-"}</code></td>
                      <td><code>{row.attemptType || "-"}</code></td>
                      <td><span className={`debug-chip ${row.result === "triggered" ? "ok" : row.result === "blocked" ? "warn" : "error"}`}>{row.result}</span></td>
                      <td><code>{row.reason || "-"}</code></td>
                      <td>{row.actorUsername ? `@${row.actorUsername}` : "-"}</td>
                      <td><code>{row.requestId || "-"}</code></td>
                      <td className="small">
                          {(row.beforeTriggeredAt ? formatDateTime(row.beforeTriggeredAt) : "-")} ({row.beforeTriggerSource || "-"}) -&gt;{" "}
                        {(row.afterTriggeredAt ? formatDateTime(row.afterTriggeredAt) : "-")} ({row.afterTriggerSource || "-"})
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "time_capsule" && (
          <div className="stack">
            <div className="row">
              <h2>Gesperrte Time-Capsules</h2>
              <button onClick={() => loadTimeCapsules(token)}>Aktualisieren</button>
            </div>
            <div className="grid4">
              <CardStat title="Gesperrte Capsules" value={timeCapsuleItems.length} />
              <CardStat
                title="Naechster Unlock"
                value={timeCapsuleItems[0]?.unlocksAt ? formatDateTime(timeCapsuleItems[0].unlocksAt) : "-"}
              />
            </div>
            {timeCapsuleItems.length === 0 && <p>Keine gesperrten Time-Capsules vorhanden.</p>}
            {timeCapsuleItems.length > 0 && (
              <div className="capsule-grid">
                {timeCapsuleItems.map((item) => (
                  <article key={item.photoId} className="capsule-card">
                    <div className="row">
                      <strong style={{ color: item.user.favoriteColor || undefined }}>{item.user.username}</strong>
                      <span className="small">{item.capsuleMode || "capsule"}</span>
                    </div>
                    <div className="photo-grid">
                      <a href={item.previewUrl} target="_blank" rel="noreferrer">
                        <img src={item.previewUrl} alt={`${item.user.username} capsule`} />
                      </a>
                      {item.secondPreviewUrl && (
                        <a href={item.secondPreviewUrl} target="_blank" rel="noreferrer">
                          <img src={item.secondPreviewUrl} alt={`${item.user.username} capsule second`} />
                        </a>
                      )}
                    </div>
                    <div className="settings-grid">
                      <p><strong>Tag:</strong> {new Date(`${item.day}T00:00:00`).toLocaleDateString()}</p>
                      <p><strong>Gecapsuled:</strong> {formatDateTime(item.capsuledAt)}</p>
                      <p><strong>Unlock:</strong> {item.unlocksAt ? formatDateTime(item.unlocksAt) : "-"}</p>
                    </div>
                  </article>
                ))}
              </div>
            )}
          </div>
        )}

        {activeTab === "debug" && (
          <div className="stack">
            <div className="debug-toolbar">
              <div className="debug-toolbar-head">
                <div className="stack" style={{ marginBottom: 0 }}>
                  <h2>Debug-Logs</h2>
                  <p className="small">Filter, Export und Loeschen arbeiten immer auf demselben Zeitraum.</p>
                </div>
                <div className="debug-actions">
                  <button onClick={() => loadDebugLogs(token, debugUserFilter, debugSinceHours)}>Aktualisieren</button>
                  <button className="danger" onClick={onDeleteDebugLogs}>Logs loeschen</button>
                </div>
              </div>
              <div className="debug-filters">
                <label>
                  Nutzer
                  <select value={debugUserFilter} onChange={(e) => setDebugUserFilter(Number(e.target.value))}>
                    <option value={0}>Alle Nutzer</option>
                    {users.map((u) => (
                      <option key={u.id} value={u.id}>@{u.username}</option>
                    ))}
                  </select>
                </label>
                <div className="debug-range">
                  <span className="small"><strong>Zeitraum</strong></span>
                  <div className="debug-range-buttons">
                    {[1, 12, 24].map((hours) => (
                      <button
                        key={hours}
                        type="button"
                        className={debugSinceHours === hours ? "active" : ""}
                        onClick={() => setDebugSinceHours(hours as 1 | 12 | 24)}
                      >
                        {hours}h
                      </button>
                    ))}
                  </div>
                </div>
              </div>
              <div className="debug-summary-grid">
                <CardStat title="Logs im Filter" value={debugSummary.total} />
                <CardStat title="Betroffene Nutzer" value={debugSummary.uniqueUsers} />
                <CardStat title="Haeufigster Typ" value={debugTypeLabel(debugSummary.topType)} />
                <CardStat title="Juengster Eintrag" value={debugSummary.newestAt ? formatDateTime(debugSummary.newestAt) : "-"} />
              </div>
              <p className="small">
                {debugFilterInfo.since
                  ? `Zeige Logs seit ${formatDateTime(debugFilterInfo.since)} (Serverzeit).`
                  : `Zeige Logs fuer die letzten ${debugSinceHours}h.`}
              </p>
              <div className="debug-export-grid">
                <article className="debug-export-card">
                  <div className="stack" style={{ marginBottom: 0 }}>
                    <strong>Nutzer-Export</strong>
                    <span className="small">Exportiert den aktuell ausgewaehlten Nutzer im aktiven Zeitraum.</span>
                  </div>
                  <div className="row">
                    <button
                      onClick={() => onDownloadUserLogs(debugSinceHours, "csv")}
                      disabled={debugUserFilter <= 0}
                    >
                      CSV herunterladen
                    </button>
                    <button
                      className="accent"
                      onClick={() => onDownloadUserLogs(debugSinceHours, "json")}
                      disabled={debugUserFilter <= 0}
                    >
                      JSON herunterladen
                    </button>
                  </div>
                </article>
                <article className="debug-export-card">
                  <div className="stack" style={{ marginBottom: 0 }}>
                    <strong>Gesamt-Export</strong>
                    <span className="small">Exportiert alle Debug-Logs im aktiven Zeitraum.</span>
                  </div>
                  <div className="row">
                    <button className="accent" onClick={() => onDownloadAllLogs(debugSinceHours, "csv")}>
                      CSV herunterladen
                    </button>
                    <button onClick={() => onDownloadAllLogs(debugSinceHours, "json")}>
                      JSON herunterladen
                    </button>
                  </div>
                </article>
              </div>
            </div>
            {debugLogs.length === 0 && <p>Keine Debug-Eintraege vorhanden.</p>}
            {debugLogs.length > 0 && (
              <div className="debug-table-wrap">
                <table className="table debug-table">
                  <thead>
                    <tr>
                      <th>Zeit</th>
                      <th>Nutzer</th>
                      <th>Geraet / App</th>
                      <th>Session / Request</th>
                      <th>Typ</th>
                      <th>Nachricht</th>
                      <th>Meta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {debugLogs.map((row) => {
                      const metaHint = debugMetaHint(row.meta || "");
                      return (
                        <tr key={row.id}>
                          <td className="debug-time-cell">{formatDateTime(row.createdAt)}</td>
                          <td className="debug-user-cell">@{row.user?.username || "-"}</td>
                          <td>
                            <div className="debug-device">
                              <strong>{row.deviceName || "-"}</strong>
                              <span className="small">{row.appVersion || "-"}</span>
                            </div>
                          </td>
                          <td>
                            <div className="debug-type-stack">
                              <code className="debug-type-code">{row.sessionId || "-"}</code>
                              <code className="debug-type-code">{row.requestId || "-"}</code>
                            </div>
                          </td>
                          <td>
                            <div className="debug-type-stack">
                              <span className={`debug-chip ${debugTypeClass(row.type)}`}>{debugTypeLabel(row.type)}</span>
                              <code className="debug-type-code">{row.type}</code>
                            </div>
                          </td>
                          <td>
                            <div className="debug-message-cell">{row.message || "-"}</div>
                          </td>
                          <td>
                            <div className="debug-meta-cell">
                              {metaHint ? <span className="debug-chip info">{metaHint}</span> : null}
                              <code>{row.meta || "-"}</code>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        )}

        {activeTab === "reports" && (
          <div className="stack">
            <div className="row">
              <h2>Reports</h2>
              <div className="row">
                <select value={reportUserFilter} onChange={(e) => setReportUserFilter(Number(e.target.value))}>
                  <option value={0}>Alle Nutzer</option>
                  {users.map((u) => (
                    <option key={u.id} value={u.id}>@{u.username}</option>
                  ))}
                </select>
                <select value={reportTypeFilter} onChange={(e) => setReportTypeFilter(e.target.value as "" | "bug" | "idea")}>
                  <option value="">Alle Typen</option>
                  <option value="bug">Bug</option>
                  <option value="idea">Idee</option>
                </select>
                <select value={reportStatusFilter} onChange={(e) => setReportStatusFilter(e.target.value as "" | "open" | "in_review" | "done" | "rejected")}>
                  <option value="">Alle Status</option>
                  <option value="open">Offen</option>
                  <option value="in_review">In Bearbeitung</option>
                  <option value="done">Erledigt</option>
                  <option value="rejected">Abgelehnt</option>
                </select>
                <button onClick={() => loadReports(token, reportUserFilter, reportTypeFilter, reportStatusFilter)}>Aktualisieren</button>
                <button className="danger" disabled={!hasReportDeleteFilter} onClick={() => void onDeleteFilteredReports()}>
                  Gefilterte Reports loeschen
                </button>
              </div>
            </div>
            <p className="small">Bulk-Loeschen wirkt auf alle Reports, die zum gesetzten Filter passen, nicht nur auf die aktuell geladene Tabelle.</p>
            {reports.length === 0 && <p>Keine Reports vorhanden.</p>}
            <table className="table">
              <thead>
                <tr>
                  <th>Zeit</th>
                  <th>Nutzer</th>
                  <th>Typ</th>
                  <th>Text</th>
                  <th>Status</th>
                  <th>GitHub</th>
                  <th>Aktion</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((row) => (
                  <tr key={row.id}>
                    <td>{formatDateTime(row.createdAt)}</td>
                    <td>@{row.user?.username || "-"}</td>
                    <td>{row.type === "bug" ? "Bug" : "Idee"}</td>
                    <td>{row.body}</td>
                    <td>
                      <select
                        value={row.status}
                        onChange={(e) =>
                          void onUpdateReportStatus(
                            row.id,
                            e.target.value as "open" | "in_review" | "done" | "rejected",
                            row.githubIssueNumber ?? null
                          )
                        }
                      >
                        <option value="open">Offen</option>
                        <option value="in_review">In Bearbeitung</option>
                        <option value="done">Erledigt</option>
                        <option value="rejected">Abgelehnt</option>
                      </select>
                    </td>
                    <td>{row.githubIssueNumber ? `#${row.githubIssueNumber}` : "-"}</td>
                    <td>
                      <button className="danger" onClick={() => void onDeleteReport(row.id)}>Loeschen</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {activeTab === "settings" && (
          <div className="stack">
            <article className="settings-current">
              <h2>Aktuell gueltige Einstellungen</h2>
              <div className="settings-grid">
                <p><strong>Prompt-Fenster:</strong> {savedSettings.promptWindowStartHour}:00 - {savedSettings.promptWindowEndHour}:00</p>
                <p><strong>Upload-Fenster:</strong> {savedSettings.uploadWindowMinutes} Minuten</p>
                <p><strong>Feed-Kommentare pro Bild:</strong> {savedSettings.feedCommentPreviewLimit}</p>
                <p><strong>Max Upload:</strong> {savedSettings.maxUploadBytes <= 0 ? "Unbegrenzt" : `${Math.round(savedSettings.maxUploadBytes / (1024 * 1024))} MB`}</p>
                <p><strong>Notification-Text:</strong> {savedSettings.promptNotificationText}</p>
                <p><strong>Nutzer-Nachfragen aktiv:</strong> {savedSettings.userPromptRules.filter((r) => r.enabled).length}/{savedSettings.userPromptRules.length}</p>
              </div>
            </article>

            <form onSubmit={onSaveSettings} className="stack">
              <h2>Einstellungen bearbeiten</h2>
              <label>
                Prompt Start-Stunde (0-23)
                <input
                  type="number"
                  min={0}
                  max={23}
                  value={settings.promptWindowStartHour}
                  onChange={(e) => setSettings({ ...settings, promptWindowStartHour: Number(e.target.value) })}
                />
              </label>
              <label>
                Prompt Ende-Stunde (1-24)
                <input
                  type="number"
                  min={1}
                  max={24}
                  value={settings.promptWindowEndHour}
                  onChange={(e) => setSettings({ ...settings, promptWindowEndHour: Number(e.target.value) })}
                />
              </label>
              <label>
                Upload-Fenster Minuten
                <input
                  type="number"
                  min={1}
                  max={60}
                  value={settings.uploadWindowMinutes}
                  onChange={(e) => setSettings({ ...settings, uploadWindowMinutes: Number(e.target.value) })}
                />
              </label>
              <label>
                Feed-Kommentare pro Bild
                <input
                  type="number"
                  min={1}
                  max={50}
                  value={settings.feedCommentPreviewLimit}
                  onChange={(e) => setSettings({ ...settings, feedCommentPreviewLimit: Number(e.target.value) })}
                />
              </label>
              <label>
                Prompt Notification Text
                <input
                  value={settings.promptNotificationText}
                  onChange={(e) => setSettings({ ...settings, promptNotificationText: e.target.value })}
                />
              </label>
              <label>
                Max Upload Bytes
                <input
                  type="number"
                  min={0}
                  value={settings.maxUploadBytes}
                  onChange={(e) => setSettings({ ...settings, maxUploadBytes: Number(e.target.value) })}
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.maxUploadBytes <= 0}
                  onChange={(e) => setSettings({ ...settings, maxUploadBytes: e.target.checked ? 0 : 10 * 1024 * 1024 })}
                />
                Unbegrenzte Upload-Groesse
              </label>
              <div className="stack">
                <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
                  <h3>Nutzer-Nachfragen</h3>
                  <button type="button" onClick={addUserPromptRule}>Regel hinzufuegen</button>
                </div>
                <p className="small">
                  Steuert freiwillige Dialoge in der App, z. B. bei neuer Version zur Freigabe von Diagnose-/Performance-Daten.
                </p>
                {settings.userPromptRules.length === 0 && <p className="small">Keine Regeln konfiguriert.</p>}
                {settings.userPromptRules.map((rule, idx) => (
                  <article key={`${rule.id}-${idx}`} className="stack" style={{ border: "1px solid var(--border)", borderRadius: 12, padding: 12 }}>
                    <div className="row" style={{ justifyContent: "space-between", alignItems: "center" }}>
                      <strong>Regel #{idx + 1}</strong>
                      <button type="button" className="danger" onClick={() => removeUserPromptRule(idx)}>Entfernen</button>
                    </div>
                    <label>
                      Rule-ID
                      <input
                        value={rule.id}
                        onChange={(e) => updateUserPromptRule(idx, { id: e.target.value })}
                      />
                    </label>
                    <label className="checkbox">
                      <input
                        type="checkbox"
                        checked={rule.enabled}
                        onChange={(e) => updateUserPromptRule(idx, { enabled: e.target.checked })}
                      />
                      Aktiv
                    </label>
                    <label>
                      Trigger
                      <select
                        value={rule.triggerType}
                        onChange={(e) => updateUserPromptRule(idx, { triggerType: e.target.value as UserPromptRule["triggerType"] })}
                      >
                        <option value="app_version">Neue App-Version</option>
                        <option value="app_start">App-Start</option>
                        <option value="time_based">Zeitbasiert</option>
                      </select>
                    </label>
                    <label>
                      Titel
                      <input
                        value={rule.title}
                        onChange={(e) => updateUserPromptRule(idx, { title: e.target.value })}
                      />
                    </label>
                    <label>
                      Text
                      <textarea
                        value={rule.body}
                        onChange={(e) => updateUserPromptRule(idx, { body: e.target.value })}
                        rows={3}
                      />
                    </label>
                    <div className="row">
                      <label style={{ flex: 1 }}>
                        Zustimmen-Label
                        <input
                          value={rule.confirmLabel}
                          onChange={(e) => updateUserPromptRule(idx, { confirmLabel: e.target.value })}
                        />
                      </label>
                      <label style={{ flex: 1 }}>
                        Ablehnen-Label
                        <input
                          value={rule.declineLabel}
                          onChange={(e) => updateUserPromptRule(idx, { declineLabel: e.target.value })}
                        />
                      </label>
                    </div>
                    <div className="row">
                      <label style={{ flex: 1 }}>
                        Cooldown (h)
                        <input
                          type="number"
                          min={0}
                          max={720}
                          value={rule.cooldownHours}
                          onChange={(e) => updateUserPromptRule(idx, { cooldownHours: Number(e.target.value) || 0 })}
                        />
                      </label>
                      <label style={{ flex: 1 }}>
                        Prioritaet
                        <input
                          type="number"
                          min={0}
                          max={1000}
                          value={rule.priority}
                          onChange={(e) => updateUserPromptRule(idx, { priority: Number(e.target.value) || 0 })}
                        />
                      </label>
                    </div>
                  </article>
                ))}
              </div>
              <div className="row">
                <button type="button" onClick={onApplyDefaultSettings}>Standardwerte setzen (8-20, 10 Min, unbegrenzt)</button>
                <button type="submit">Settings speichern</button>
              </div>
            </form>
          </div>
        )}

        {message && <p className="message">{message}</p>}
      </section>
    </main>
  );
}

function toInputDateTime(iso: string) {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatDateTime(iso: string) {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}, ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function formatDateShort(day: string) {
  if (!day) return "-";
  const d = new Date(`${day}T00:00:00`);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getDate())}.${pad(d.getMonth() + 1)}.`;
}

function ratioPercent(value: number) {
  if (!Number.isFinite(value)) return 0;
  return Math.round(value * 100);
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx++;
  }
  return `${value.toFixed(idx === 0 ? 0 : 2)} ${units[idx]}`;
}

function formatDuration(sec: number) {
  const s = Math.max(0, Math.floor(sec));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${h}h ${m}m`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function truncateText(value: string, maxLen = 80) {
  const text = (value || "").trim();
  if (text.length <= maxLen) return text;
  return `${text.slice(0, maxLen - 1)}…`;
}

function debugTypeLabel(value: string) {
  switch (value) {
    case "dashboard_load_failed":
      return "Dashboard";
    case "profile_open_failed":
      return "Profil Fehler";
    case "crash_unhandled":
      return "Crash";
    case "profile_open_ok":
      return "Profil OK";
    default:
      return value || "Unbekannt";
  }
}

function debugTypeClass(value: string) {
  switch (value) {
    case "dashboard_load_failed":
      return "warn";
    case "profile_open_failed":
    case "crash_unhandled":
      return "error";
    case "profile_open_ok":
      return "ok";
    default:
      return "neutral";
  }
}

function CardStat({ title, value }: { title: string; value: number | string }) {
  return (
    <article className="stat">
      <h3>{title}</h3>
      <p>{value}</p>
    </article>
  );
}
