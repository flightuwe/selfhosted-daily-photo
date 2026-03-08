import { useEffect, useMemo, useState } from "react";
import {
  broadcastNotification,
  createUser,
  deleteUser,
  getSettings,
  getStats,
  listUsers,
  login,
  triggerPrompt,
  updateSettings,
  updateUser,
  type AdminStats,
  type AdminUser,
  type Settings,
} from "./api";

type Tab = "dashboard" | "events" | "users" | "settings";

const emptySettings: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 22,
  uploadWindowMinutes: 5,
  promptNotificationText: "Zeit fuer dein Daily Foto",
  maxUploadBytes: 10 * 1024 * 1024,
};

const emptyStats: AdminStats = {
  users: 0,
  photos: 0,
  devices: 0,
  prompts: 0,
};

export function App() {
  const [token, setToken] = useState<string>(() => localStorage.getItem("admin-token") || "");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [settings, setSettings] = useState<Settings>(emptySettings);
  const [stats, setStats] = useState<AdminStats>(emptyStats);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [message, setMessage] = useState("");
  const [activeTab, setActiveTab] = useState<Tab>("dashboard");

  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newIsAdmin, setNewIsAdmin] = useState(false);

  const [resetPassword, setResetPassword] = useState<Record<number, string>>({});
  const [broadcastBody, setBroadcastBody] = useState("Server-Test: Bitte App öffnen und Daily Foto posten.");
  const [updateNoticeVersion, setUpdateNoticeVersion] = useState("0.2.6");

  const isLoggedIn = useMemo(() => token.length > 0, [token]);

  useEffect(() => {
    if (!token) return;
    void loadAdminData(token);
  }, [token]);

  async function loadAdminData(authToken: string) {
    try {
      const [s, st, u] = await Promise.all([getSettings(authToken), getStats(authToken), listUsers(authToken)]);
      setSettings(s);
      setStats(st);
      setUsers(u);
    } catch (err) {
      setMessage((err as Error).message);
      setToken("");
    }
  }

  async function refreshAll() {
    if (!token) return;
    await loadAdminData(token);
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
      setMessage("Settings gespeichert");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onTriggerEvent() {
    setMessage("");
    try {
      await triggerPrompt(token);
      setMessage("Daily Event ausgelöst. Nutzer können Prompt-Fotos hochladen.");
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onBroadcast() {
    setMessage("");
    try {
      const result = await broadcastNotification(token, broadcastBody);
      setMessage(`Benachrichtigung an ${result.sentTo} registrierte Geräte gesendet (FCM nötig für echte Pushes).`);
    } catch (err) {
      setMessage((err as Error).message);
    }
  }

  async function onSendUpdateNotice() {
    const text = `Update verfügbar: Version ${updateNoticeVersion}. Bitte App aktualisieren.`;
    setBroadcastBody(text);
    try {
      const result = await broadcastNotification(token, text);
      setMessage(`Update-Hinweis an ${result.sentTo} Geräte gesendet.`);
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

  function logout() {
    localStorage.removeItem("admin-token");
    setToken("");
    setMessage("");
  }

  if (!isLoggedIn) {
    return (
      <main className="page">
        <section className="panel">
          <h1>Selfhosted BeReal Admin</h1>
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
    <main className="page">
      <section className="panel wide">
        <div className="row">
          <h1>Admin Panel</h1>
          <div className="row">
            <button onClick={refreshAll}>Reload</button>
            <button onClick={logout}>Logout</button>
          </div>
        </div>

        <div className="tabs">
          <button className={activeTab === "dashboard" ? "tab active" : "tab"} onClick={() => setActiveTab("dashboard")}>Dashboard</button>
          <button className={activeTab === "events" ? "tab active" : "tab"} onClick={() => setActiveTab("events")}>Events & Notifications</button>
          <button className={activeTab === "users" ? "tab active" : "tab"} onClick={() => setActiveTab("users")}>Benutzerverwaltung</button>
          <button className={activeTab === "settings" ? "tab active" : "tab"} onClick={() => setActiveTab("settings")}>Einstellungen</button>
        </div>

        {activeTab === "dashboard" && (
          <div className="grid4">
            <CardStat title="Nutzer" value={stats.users} />
            <CardStat title="Geräte" value={stats.devices} />
            <CardStat title="Fotos" value={stats.photos} />
            <CardStat title="Prompt-Events" value={stats.prompts} />
          </div>
        )}

        {activeTab === "events" && (
          <div className="stack">
            <button className="accent" onClick={onTriggerEvent}>Daily Event manuell auslösen</button>

            <label>
              Custom Nachricht an alle Geräte
              <input value={broadcastBody} onChange={(e) => setBroadcastBody(e.target.value)} />
            </label>
            <button onClick={onBroadcast}>Benachrichtigung senden</button>

            <label>
              Update-Version für Hinweis
              <input value={updateNoticeVersion} onChange={(e) => setUpdateNoticeVersion(e.target.value)} />
            </label>
            <button onClick={onSendUpdateNotice}>Update-Hinweis senden</button>
            <small>Hinweis: echte Push-Zustellung funktioniert erst mit aktivem FCM-Provider.</small>
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
            <table className="table">
              <thead>
                <tr>
                  <th>User</th>
                  <th>Rolle</th>
                  <th>Fotos</th>
                  <th>Geräte</th>
                  <th>Passwort ändern</th>
                  <th>Löschen</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td>{u.username}</td>
                    <td>
                      <select value={u.isAdmin ? "admin" : "user"} onChange={(e) => onToggleAdmin(u, e.target.value === "admin")}>
                        <option value="user">User</option>
                        <option value="admin">Admin</option>
                      </select>
                    </td>
                    <td>{u.photoCount}</td>
                    <td>{u.deviceCount}</td>
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

        {activeTab === "settings" && (
          <form onSubmit={onSaveSettings} className="stack">
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
                min={1000000}
                value={settings.maxUploadBytes}
                onChange={(e) => setSettings({ ...settings, maxUploadBytes: Number(e.target.value) })}
              />
            </label>
            <button type="submit">Settings speichern</button>
          </form>
        )}

        {message && <p className="message">{message}</p>}
      </section>
    </main>
  );
}

function CardStat({ title, value }: { title: string; value: number }) {
  return (
    <article className="stat">
      <h3>{title}</h3>
      <p>{value}</p>
    </article>
  );
}
