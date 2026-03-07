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

export async function triggerPrompt(token: string): Promise<void> {
  const res = await fetch(`${apiBase}/admin/prompt/trigger`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
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
