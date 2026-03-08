export type AuthResponse = {
  token: string;
  user: { id: number; username: string; isAdmin: boolean };
};

export type Settings = {
  promptWindowStartHour: number;
  promptWindowEndHour: number;
  uploadWindowMinutes: number;
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
};

export type AdminUser = {
  id: number;
  username: string;
  isAdmin: boolean;
  createdAt: string;
  photoCount: number;
  deviceCount: number;
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

export type ChatItem = {
  id: number;
  body: string;
  createdAt: string;
  user: { id: number; username: string };
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

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(body.error || "Request failed");
  }
  return res.json();
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
  return parse<Settings>(res);
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
  return parse<Settings>(res);
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

export async function getAdminFeed(token: string, day?: string): Promise<FeedItem[]> {
  const qs = day ? `?day=${encodeURIComponent(day)}` : "";
  const res = await fetch(`${apiBase}/admin/feed${qs}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: FeedItem[] }>(res);
  return data.items;
}

export async function getChat(token: string): Promise<ChatItem[]> {
  const res = await fetch(`${apiBase}/chat`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: ChatItem[] }>(res);
  return data.items;
}

export async function sendChat(token: string, body: string): Promise<void> {
  const res = await fetch(`${apiBase}/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  await parse(res);
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
