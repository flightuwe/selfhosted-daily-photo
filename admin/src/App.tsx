import { useEffect, useMemo, useState } from "react";
import { createUser, getSettings, login, triggerPrompt, updateSettings, type Settings } from "./api";

const emptySettings: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 22,
  uploadWindowMinutes: 5,
  promptNotificationText: "Zeit fuer dein Daily Foto",
  maxUploadBytes: 10 * 1024 * 1024,
};

export function App() {
  const [token, setToken] = useState<string>(() => localStorage.getItem("admin-token") || "");
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [settings, setSettings] = useState<Settings>(emptySettings);
  const [message, setMessage] = useState("");

  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newIsAdmin, setNewIsAdmin] = useState(false);

  const isLoggedIn = useMemo(() => token.length > 0, [token]);

  useEffect(() => {
    if (!token) return;
    getSettings(token)
      .then(setSettings)
      .catch((err) => {
        setMessage(err.message);
        setToken("");
      });
  }, [token]);

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
      setMessage("Daily Event ausgelöst. Nutzer koennen jetzt Prompt-Fotos hochladen.");
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
      <section className="panel">
        <div className="row">
          <h1>Admin Panel</h1>
          <button onClick={logout}>Logout</button>
        </div>

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
            Notification Text
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

        <button className="accent" onClick={onTriggerEvent}>
          Daily Event manuell auslösen
        </button>

        <h2>User anlegen</h2>
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
            Admin
          </label>
          <button type="submit">User erstellen</button>
        </form>

        {message && <p className="message">{message}</p>}
      </section>
    </main>
  );
}
