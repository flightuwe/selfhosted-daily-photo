export type AuthResponse = {
  token: string;
  user: { id: number; username: string; isAdmin: boolean };
};

export type Settings = {
  promptWindowStartHour: number;
  promptWindowEndHour: number;
  uploadWindowMinutes: number;
  feedCommentPreviewLimit: number;
  promptNotificationText: string;
  maxUploadBytes: number;
  chatCommandEnabled: boolean;
  chatCommandValue: string;
  chatCommandTrigger: boolean;
  chatCommandSendPush: boolean;
  chatCommandPushText: string;
  chatCommandEchoChat: boolean;
  chatCommandEchoText: string;
};

export type AdminStats = {
  users: number;
  photos: number;
  devices: number;
  prompts: number;
  totalImages: number;
  runningDays: number;
  storageBytes: number;
};

export type AdminUser = {
  id: number;
  username: string;
  isAdmin: boolean;
  createdAt: string;
  invitedById?: number;
  invitedBy?: string;
  invitedAt?: string;
  photoCount: number;
  deviceCount: number;
  deviceNames?: string[];
  deviceDetails?: Array<{ name: string; appVersion?: string }>;
  lastAppVersion?: string;
  lastError?: string;
  lastErrorAt?: string;
  lastProfileOkAt?: string;
};

export type DebugLogItem = {
  id: number;
  createdAt: string;
  type: string;
  message: string;
  meta?: string;
  appVersion?: string;
  deviceName?: string;
  user: { id: number; username: string };
};

export type DebugLogsResponse = {
  items: DebugLogItem[];
  sinceHours: number;
  since: string;
  serverNow: string;
};

export type AdminReportItem = {
  id: number;
  type: "bug" | "idea";
  body: string;
  source: string;
  status: "open" | "in_review" | "done" | "rejected";
  githubIssueNumber?: number | null;
  createdAt: string;
  updatedAt: string;
  user: { id: number; username: string; favoriteColor?: string };
};

export type FeedPhoto = {
  id: number;
  day: string;
  promptOnly: boolean;
  caption?: string;
  url: string;
  secondUrl?: string;
  createdAt: string;
};

export type FeedItem = {
  isLate: boolean;
  triggerSource?: string;
  requestedByUser?: string;
  photo: FeedPhoto;
  user: { id: number; username: string };
};

export type MonthlyRecap = {
  month: string;
  monthLabel: string;
  yourMoments: number;
  mostReliableUser?: { id: number; username: string; favoriteColor?: string; count: number };
  topSpontaneous: Array<{ day: string; userId: number; username: string; minutesAfterTrigger: number; createdAt: string }>;
};

export type AdminFeedResponse = {
  items: FeedItem[];
  monthRecap?: MonthlyRecap | null;
};

export type ChatItem = {
  id: number;
  body: string;
  createdAt: string;
  user: { id: number; username: string };
};

export type ChatSendResult = {
  id?: number;
  body?: string;
  source?: string;
  command?: boolean;
  report?: boolean;
  reportId?: number;
  reportType?: string;
  reportStatus?: string;
  message?: string;
};

export type CalendarItem = {
  day: string;
  plannedAt: string;
  isManual: boolean;
  source: "auto" | "manual";
  triggeredAt?: string | null;
  uploadUntil?: string | null;
  triggerSource?: string;
  requestedByUser?: string;
};

export type AdminHistoryUserActivity = {
  userId: number;
  username: string;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  requestCount: number;
  posted: boolean;
  postedPrompt: boolean;
  postedExtra: boolean;
};

export type AdminHistoryAnalytics = {
  promptPhotoRatio: number;
  extraPhotoRatio: number;
  capsulePhotoRatio: number;
  promptUserRatio: number;
  extraUserRatio: number;
  avgRequestsPerOnline: number;
  triggerDelayMinutes: number;
  onTimeTrigger: boolean;
  hasTriggerPerformance: boolean;
  totalRequests: number;
};

export type AdminHistoryDay = {
  day: string;
  plannedAt?: string | null;
  triggeredAt?: string | null;
  uploadUntil?: string | null;
  source: "auto" | "manual";
  triggerSource?: string;
  requestedByUser?: string;
  onlineUsersCount?: number | null;
  postedUsersCount: number;
  dailyMomentUsersCount: number;
  extraUsersCount: number;
  photoCount: number;
  dailyMomentPhotoCount: number;
  extraPhotoCount: number;
  timeCapsuleCount: number;
  privateCapsuleCount: number;
  commentCount: number;
  reactionCount: number;
  chatMessageCount: number;
  debugErrorCount?: number;
  onlineTrackingAvailable: boolean;
  userActivity?: AdminHistoryUserActivity[] | null;
  analytics?: AdminHistoryAnalytics;
};

export type AdminHistoryLeaderboardEntry = {
  userId: number;
  username: string;
  postedDays: number;
  promptDays: number;
  extraDays: number;
  onlineDays?: number;
  reliabilityScore?: number;
  extraBiasScore?: number;
  participation7d?: number;
  participation30d?: number;
  participationDelta?: number;
};

export type AdminHistoryAnomaly = {
  day: string;
  severity: "low" | "medium" | "high";
  reason: string;
  details?: string;
};

export type AdminHistoryResponse = {
  items: AdminHistoryDay[];
  days: number;
  offset: number;
  excludeEmpty?: boolean;
  onlineTrackingSince?: string;
  leaderboard?: {
    reliableTop?: AdminHistoryLeaderboardEntry[];
    extraHeavyTop?: AdminHistoryLeaderboardEntry[];
  };
  anomalies?: AdminHistoryAnomaly[];
};

export type AdminTimeCapsuleItem = {
  photoId: number;
  day: string;
  capsuleMode?: string;
  capsuledAt: string;
  unlocksAt?: string | null;
  previewUrl: string;
  secondPreviewUrl?: string;
  user: { id: number; username: string; favoriteColor?: string };
};

export type ChatCommand = {
  id: number;
  name: string;
  command: string;
  action: "trigger_moment" | "clear_chat" | "broadcast_push" | "send_chat_message";
  enabled: boolean;
  requireAdmin: boolean;
  sendPush: boolean;
  postChat: boolean;
  pushText: string;
  responseText: string;
  cooldownSecond: number;
  lastUsedAt?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type SystemComponent = {
  name: string;
  ok: boolean;
  message: string;
};

export type SystemHealth = {
  ok: boolean;
  version: string;
  provider: string;
  time: string;
  uploadSizeBytes: number;
  latestPrompt?: {
    day?: string;
    triggeredAt?: string | null;
    uploadUntil?: string | null;
    triggerSource?: string;
    requestedByUser?: string;
  };
  components: SystemComponent[];
  metrics?: {
    startedAt?: string;
    uptimeSec?: number;
    requestsTotal?: number;
    errorsTotal?: number;
    errors4xx?: number;
    errors5xx?: number;
    errorRatePercent?: number;
    p95LatencyMs?: number;
    recentRequestsCnt?: number;
    push?: {
      sent?: number;
      failed?: number;
      invalidTokens?: number;
      errors?: number;
    };
  };
};

const apiBase = import.meta.env.VITE_API_BASE || "/api";

const settingsDefaults: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 20,
  uploadWindowMinutes: 10,
  feedCommentPreviewLimit: 10,
  promptNotificationText: "Zeit fuer dein Daily Foto",
  maxUploadBytes: 0,
  chatCommandEnabled: false,
  chatCommandValue: "-moment",
  chatCommandTrigger: true,
  chatCommandSendPush: true,
  chatCommandPushText: "{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.",
  chatCommandEchoChat: true,
  chatCommandEchoText: "Moment wurde von {user} angefordert.",
};

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(body.error || "Request failed");
  }
  return res.json();
}

function normalizeSettings(raw: any): Settings {
  return {
    promptWindowStartHour: Number(raw?.promptWindowStartHour ?? raw?.PromptWindowStartHour ?? settingsDefaults.promptWindowStartHour),
    promptWindowEndHour: Number(raw?.promptWindowEndHour ?? raw?.PromptWindowEndHour ?? settingsDefaults.promptWindowEndHour),
    uploadWindowMinutes: Number(raw?.uploadWindowMinutes ?? raw?.UploadWindowMinutes ?? settingsDefaults.uploadWindowMinutes),
    feedCommentPreviewLimit: Number(raw?.feedCommentPreviewLimit ?? raw?.FeedCommentPreviewLimit ?? settingsDefaults.feedCommentPreviewLimit),
    promptNotificationText: String(raw?.promptNotificationText ?? raw?.PromptNotificationText ?? settingsDefaults.promptNotificationText),
    maxUploadBytes: Number(raw?.maxUploadBytes ?? raw?.MaxUploadBytes ?? settingsDefaults.maxUploadBytes),
    chatCommandEnabled: Boolean(raw?.chatCommandEnabled ?? raw?.ChatCommandEnabled ?? settingsDefaults.chatCommandEnabled),
    chatCommandValue: String(raw?.chatCommandValue ?? raw?.ChatCommandValue ?? settingsDefaults.chatCommandValue),
    chatCommandTrigger: Boolean(raw?.chatCommandTrigger ?? raw?.ChatCommandTrigger ?? settingsDefaults.chatCommandTrigger),
    chatCommandSendPush: Boolean(raw?.chatCommandSendPush ?? raw?.ChatCommandSendPush ?? settingsDefaults.chatCommandSendPush),
    chatCommandPushText: String(raw?.chatCommandPushText ?? raw?.ChatCommandPushText ?? settingsDefaults.chatCommandPushText),
    chatCommandEchoChat: Boolean(raw?.chatCommandEchoChat ?? raw?.ChatCommandEchoChat ?? settingsDefaults.chatCommandEchoChat),
    chatCommandEchoText: String(raw?.chatCommandEchoText ?? raw?.ChatCommandEchoText ?? settingsDefaults.chatCommandEchoText),
  };
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  const res = await fetch(`${apiBase}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  return parse<AuthResponse>(res);
}

export async function getSettings(token: string): Promise<Settings> {
  const res = await fetch(`${apiBase}/admin/settings`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<any>(res);
  return normalizeSettings(data);
}

export async function updateSettings(token: string, settings: Settings): Promise<Settings> {
  const res = await fetch(`${apiBase}/admin/settings`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(settings),
  });
  const data = await parse<any>(res);
  return normalizeSettings(data);
}

export async function getStats(token: string): Promise<AdminStats> {
  const res = await fetch(`${apiBase}/admin/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminStats>(res);
}

export async function triggerPrompt(token: string): Promise<void> {
  const res = await fetch(`${apiBase}/admin/prompt/trigger`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function resetTodayPrompt(token: string): Promise<{ day: string; message: string }> {
  const res = await fetch(`${apiBase}/admin/prompt/reset-today`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ day: string; message: string }>(res);
}

export async function broadcastNotification(token: string, body: string): Promise<{ sentTo: number; provider: string }> {
  const res = await fetch(`${apiBase}/admin/notifications/broadcast`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<{ sentTo: number; provider: string }>(res);
}

export async function notifyUser(
  token: string,
  userId: number,
  body: string
): Promise<{ sentTo: number; failed: number; provider: string; username: string; devices: number }> {
  const res = await fetch(`${apiBase}/admin/notifications/user/${userId}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<{ sentTo: number; failed: number; provider: string; username: string; devices: number }>(res);
}

export async function listUsers(token: string): Promise<AdminUser[]> {
  const res = await fetch(`${apiBase}/admin/users`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminUser[] }>(res);
  return data.items;
}

export async function createUser(token: string, username: string, password: string, isAdmin: boolean): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ username, password, isAdmin }),
  });
  await parse(res);
}

export async function updateUser(token: string, id: number, payload: { password?: string; isAdmin?: boolean }): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  await parse(res);
}

export async function deleteUser(token: string, id: number): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getAdminFeed(token: string, day?: string): Promise<AdminFeedResponse> {
  const qs = day ? `?day=${encodeURIComponent(day)}` : "";
  const res = await fetch(`${apiBase}/admin/feed${qs}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminFeedResponse>(res);
}

export async function getChat(token: string): Promise<ChatItem[]> {
  const res = await fetch(`${apiBase}/chat`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: ChatItem[] }>(res);
  return data.items;
}

export async function sendChat(token: string, body: string): Promise<ChatSendResult> {
  const res = await fetch(`${apiBase}/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<ChatSendResult>(res);
}

export async function clearChat(token: string): Promise<void> {
  const res = await fetch(`${apiBase}/admin/chat/clear`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getChatCommands(token: string): Promise<ChatCommand[]> {
  const res = await fetch(`${apiBase}/admin/chat/commands`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: ChatCommand[] }>(res);
  return data.items;
}

export async function createChatCommand(token: string, body: Omit<ChatCommand, "id" | "lastUsedAt" | "createdAt" | "updatedAt">): Promise<ChatCommand> {
  const res = await fetch(`${apiBase}/admin/chat/commands`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
  return parse<ChatCommand>(res);
}

export async function updateChatCommand(token: string, id: number, body: Omit<ChatCommand, "id" | "lastUsedAt" | "createdAt" | "updatedAt">): Promise<ChatCommand> {
  const res = await fetch(`${apiBase}/admin/chat/commands/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
  return parse<ChatCommand>(res);
}

export async function deleteChatCommand(token: string, id: number): Promise<void> {
  const res = await fetch(`${apiBase}/admin/chat/commands/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getCalendar(token: string, days = 7): Promise<CalendarItem[]> {
  const res = await fetch(`${apiBase}/admin/calendar?days=${days}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: CalendarItem[] }>(res);
  return data.items;
}

export async function getAdminHistory(token: string, days = 30, offset = 0, excludeEmpty = true): Promise<AdminHistoryResponse> {
  const qs = new URLSearchParams();
  qs.set("days", String(days));
  qs.set("offset", String(offset));
  qs.set("excludeEmpty", String(excludeEmpty));
  const res = await fetch(`${apiBase}/admin/history?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminHistoryResponse>(res);
  return {
    items: (data.items || []).map((item) => ({
      ...item,
      userActivity: item.userActivity || [],
    })),
    days: data.days ?? days,
    offset: data.offset ?? offset,
    excludeEmpty: data.excludeEmpty ?? excludeEmpty,
    onlineTrackingSince: data.onlineTrackingSince || "",
    leaderboard: {
      reliableTop: data.leaderboard?.reliableTop || [],
      extraHeavyTop: data.leaderboard?.extraHeavyTop || [],
    },
    anomalies: data.anomalies || [],
  };
}

export async function getAdminTimeCapsules(token: string): Promise<AdminTimeCapsuleItem[]> {
  const res = await fetch(`${apiBase}/admin/time-capsules`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminTimeCapsuleItem[] }>(res);
  return data.items || [];
}

export async function updateCalendarDay(token: string, day: string, plannedAt: string): Promise<CalendarItem> {
  const res = await fetch(`${apiBase}/admin/calendar/${encodeURIComponent(day)}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ plannedAt }),
  });
  return parse<CalendarItem>(res);
}

export async function getSystemHealth(token: string): Promise<SystemHealth> {
  const res = await fetch(`${apiBase}/admin/system/health`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<SystemHealth>(res);
}

export async function getDebugLogs(token: string, userId?: number, limit = 150, sinceHours = 24): Promise<DebugLogsResponse> {
  const qs = new URLSearchParams();
  qs.set("limit", String(limit));
  qs.set("sinceHours", String(sinceHours));
  if (userId && userId > 0) qs.set("userId", String(userId));
  const res = await fetch(`${apiBase}/admin/debug/logs?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<DebugLogsResponse>(res);
  return {
    items: data.items || [],
    sinceHours: data.sinceHours ?? sinceHours,
    since: data.since || "",
    serverNow: data.serverNow || "",
  };
}

export async function deleteDebugLogs(
  token: string,
  opts?: { userId?: number; sinceHours?: number }
): Promise<{ deletedCount: number; userId: number; sinceHours: number }> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  qs.set("sinceHours", String(opts?.sinceHours ?? 24));
  const res = await fetch(`${apiBase}/admin/debug/logs?${qs.toString()}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ deletedCount: number; userId: number; sinceHours: number }>(res);
}

export async function downloadDebugLogs(
  token: string,
  opts?: { userId?: number; sinceHours?: number; format?: "csv" | "json"; limit?: number }
): Promise<void> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  qs.set("sinceHours", String(opts?.sinceHours ?? 24));
  qs.set("format", opts?.format ?? "csv");
  qs.set("limit", String(opts?.limit ?? 5000));

  const res = await fetch(`${apiBase}/admin/debug/logs/export?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }

  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename="?([^"]+)"?/i);
  const fallbackExt = (opts?.format ?? "csv") === "json" ? "json" : "csv";
  const filename = fileMatch?.[1] || `debug-logs-last-24h.${fallbackExt}`;

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function getReports(
  token: string,
  opts?: { userId?: number; type?: "" | "bug" | "idea"; status?: "" | "open" | "in_review" | "done" | "rejected"; limit?: number }
): Promise<AdminReportItem[]> {
  const qs = new URLSearchParams();
  qs.set("limit", String(opts?.limit ?? 200));
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.type) qs.set("type", opts.type);
  if (opts?.status) qs.set("status", opts.status);
  const res = await fetch(`${apiBase}/admin/reports?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminReportItem[] }>(res);
  return data.items || [];
}

export async function updateReport(
  token: string,
  id: number,
  payload: { status: "open" | "in_review" | "done" | "rejected"; githubIssueNumber?: number | null }
): Promise<AdminReportItem> {
  const res = await fetch(`${apiBase}/admin/reports/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  return parse<AdminReportItem>(res);
}

export async function deleteReport(
  token: string,
  id: number
): Promise<{ ok: boolean; deletedId: number }> {
  const res = await fetch(`${apiBase}/admin/reports/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ ok: boolean; deletedId: number }>(res);
}

export async function deleteReports(
  token: string,
  opts?: { userId?: number; type?: "" | "bug" | "idea"; status?: "" | "open" | "in_review" | "done" | "rejected" }
): Promise<{ ok: boolean; deletedCount: number }> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.type) qs.set("type", opts.type);
  if (opts?.status) qs.set("status", opts.status);
  const res = await fetch(`${apiBase}/admin/reports?${qs.toString()}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ ok: boolean; deletedCount: number }>(res);
}
