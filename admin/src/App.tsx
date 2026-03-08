import { useEffect, useMemo, useState } from "react";
import {
  broadcastNotification,
  createChatCommand,
  clearChat,
  createUser,
  deleteChatCommand,
  deleteUser,
  getAdminFeed,
  getCalendar,
  getChat,
  getChatCommands,
  getSettings,
  getStats,
  listUsers,
  login,
  notifyUser,
  resetTodayPrompt,
  sendChat,
  triggerPrompt,
  updateCalendarDay,
  updateChatCommand,
  updateSettings,
  updateUser,
  type AdminStats,
  type ChatCommand,
  type AdminUser,
  type ChatItem,
  type CalendarItem,
  type FeedItem,
  type Settings,
} from "./api";

type Tab = "dashboard" | "events" | "commands" | "users" | "feed" | "chat" | "calendar" | "settings";

const DEFAULT_SETTINGS: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 20,
  uploadWindowMinutes: 10,
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
const emptySettings: Settings = { ...DEFAULT_SETTINGS };

const emptyStats: AdminStats = {
  users: 0,
  photos: 0,
  devices: 0,
  prompts: 0,
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

export function App() {
  const [token, setToken] = useState<string>(() => localStorage.getItem("admin-token") || "");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [settings, setSettings] = useState<Settings>(emptySettings);
  const [savedSettings, setSavedSettings] = useState<Settings>(emptySettings);
  const [stats, setStats] = useState<AdminStats>(emptyStats);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [feedItems, setFeedItems] = useState<FeedItem[]>([]);
  const [chatItems, setChatItems] = useState<ChatItem[]>([]);
  const [chatDraft, setChatDraft] = useState("");
  const [chatCommands, setChatCommands] = useState<ChatCommand[]>([]);
  const [editingCommandId, setEditingCommandId] = useState<number | null>(null);
  const [commandDraft, setCommandDraft] = useState<CommandDraft>(emptyCommandDraft);
  const [calendarItems, setCalendarItems] = useState<CalendarItem[]>([]);
  const [calendarDrafts, setCalendarDrafts] = useState<Record<string, string>>({});
  const [feedDay, setFeedDay] = useState<string>(() => new Date().toISOString().slice(0, 10));
  const [message, setMessage] = useState("");
  const [activeTab, setActiveTab] = useState<Tab>("dashboard");

  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newIsAdmin, setNewIsAdmin] = useState(false);

  const [resetPassword, setResetPassword] = useState<Record<number, string>>({});
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
    if (activeTab === "commands") {
      void loadCommands(token);
    }
  }, [token, activeTab, feedDay]);

  async function loadAdminData(authToken: string) {
    try {
      const [s, st, u, cmds] = await Promise.all([
        getSettings(authToken),
        getStats(authToken),
        listUsers(authToken),
        getChatCommands(authToken),
      ]);
      setSettings(s);
      setSavedSettings(s);
      setStats(st);
      setUsers(u);
      setChatCommands(cmds);
    } catch (err) {
      setMessage((err as Error).message);
      setToken("");
    }
  }

  async function loadFeed(authToken: string, day?: string) {
    try {
      const items = await getAdminFeed(authToken, day);
      setFeedItems(items);
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
      await sendChat(token, text);
      setChatDraft("");
      await loadChat(token);
      setMessage("Nachricht in Chat gesendet.");
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

  async function refreshAll() {
    if (!token) return;
    await loadAdminData(token);
    if (activeTab === "feed") await loadFeed(token, feedDay);
    if (activeTab === "chat") await loadChat(token);
    if (activeTab === "calendar") await loadCalendar(token);
    if (activeTab === "commands") await loadCommands(token);
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
    setActiveTab("commands");
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

  async function onApplyDefaultSettings() {
    setMessage("");
    try {
      const next = await updateSettings(token, DEFAULT_SETTINGS);
      setSettings(next);
      setSavedSettings(next);
      setMessage("Standard-Einstellungen wurden gesetzt.");
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

  function logout() {
    localStorage.removeItem("admin-token");
    setToken("");
    setMessage("");
  }

  if (!isLoggedIn) {
    return (
      <main className="page">
        <section className="panel">
          <h1>Daily Admin</h1>
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
          <button className={activeTab === "commands" ? "tab active" : "tab"} onClick={() => setActiveTab("commands")}>Chat-Commands</button>
          <button className={activeTab === "users" ? "tab active" : "tab"} onClick={() => setActiveTab("users")}>Benutzerverwaltung</button>
          <button className={activeTab === "feed" ? "tab active" : "tab"} onClick={() => setActiveTab("feed")}>Feed</button>
          <button className={activeTab === "chat" ? "tab active" : "tab"} onClick={() => setActiveTab("chat")}>Chat</button>
          <button className={activeTab === "calendar" ? "tab active" : "tab"} onClick={() => setActiveTab("calendar")}>Kalender</button>
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

        {activeTab === "feed" && (
          <div className="stack">
            <div className="row">
              <label>
                Tag
                <input type="date" value={feedDay} onChange={(e) => setFeedDay(e.target.value)} />
              </label>
              <button onClick={() => loadFeed(token, feedDay)}>Feed laden</button>
            </div>
            {feedItems.length === 0 && <p>Keine Eintraege fuer diesen Tag.</p>}
            <div className="feed-grid">
              {feedItems.map((item) => (
                <article key={`${item.user.id}-${item.photo.id}`} className="feed-card">
                  <div className="row">
                    <strong>{item.user.username}</strong>
                    {item.isLate && <span className="late">Spaet</span>}
                  </div>
                  {item.triggerSource === "chat_command" && item.requestedByUser && (
                    <p className="small"><strong>Community-Moment:</strong> von {item.requestedByUser} per Chat-Command angefordert</p>
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
                      {item.triggerSource === "chat_command" && item.requestedByUser
                        ? `Chat (${item.requestedByUser})`
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

        {activeTab === "settings" && (
          <div className="stack">
            <article className="settings-current">
              <h2>Aktuell gueltige Einstellungen</h2>
              <div className="settings-grid">
                <p><strong>Prompt-Fenster:</strong> {savedSettings.promptWindowStartHour}:00 - {savedSettings.promptWindowEndHour}:00</p>
                <p><strong>Upload-Fenster:</strong> {savedSettings.uploadWindowMinutes} Minuten</p>
                <p><strong>Max Upload:</strong> {savedSettings.maxUploadBytes <= 0 ? "Unbegrenzt" : `${Math.round(savedSettings.maxUploadBytes / (1024 * 1024))} MB`}</p>
                <p><strong>Notification-Text:</strong> {savedSettings.promptNotificationText}</p>
                <p><strong>Chat-Command:</strong> {savedSettings.chatCommandEnabled ? "aktiv" : "deaktiviert"} ({savedSettings.chatCommandValue})</p>
                <p><strong>Command-Aktionen:</strong> Trigger={savedSettings.chatCommandTrigger ? "an" : "aus"}, Push={savedSettings.chatCommandSendPush ? "an" : "aus"}, Chat={savedSettings.chatCommandEchoChat ? "an" : "aus"}</p>
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
                Prompt Notification Text
                <input
                  value={settings.promptNotificationText}
                  onChange={(e) => setSettings({ ...settings, promptNotificationText: e.target.value })}
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.chatCommandEnabled}
                  onChange={(e) => setSettings({ ...settings, chatCommandEnabled: e.target.checked })}
                />
                Chat-Command aktivieren
              </label>
              <label>
                Chat-Command Text
                <input
                  value={settings.chatCommandValue}
                  onChange={(e) => setSettings({ ...settings, chatCommandValue: e.target.value })}
                  placeholder="-moment"
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.chatCommandTrigger}
                  onChange={(e) => setSettings({ ...settings, chatCommandTrigger: e.target.checked })}
                />
                Aktion: Moment triggern
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.chatCommandSendPush}
                  onChange={(e) => setSettings({ ...settings, chatCommandSendPush: e.target.checked })}
                />
                Aktion: Push an alle senden
              </label>
              <label>
                Push-Text (Platzhalter: {"{user}"})
                <input
                  value={settings.chatCommandPushText}
                  onChange={(e) => setSettings({ ...settings, chatCommandPushText: e.target.value })}
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={settings.chatCommandEchoChat}
                  onChange={(e) => setSettings({ ...settings, chatCommandEchoChat: e.target.checked })}
                />
                Aktion: Chat-Meldung erzeugen
              </label>
              <label>
                Chat-Meldungstext (Platzhalter: {"{user}"})
                <input
                  value={settings.chatCommandEchoText}
                  onChange={(e) => setSettings({ ...settings, chatCommandEchoText: e.target.value })}
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

function CardStat({ title, value }: { title: string; value: number }) {
  return (
    <article className="stat">
      <h3>{title}</h3>
      <p>{value}</p>
    </article>
  );
}
