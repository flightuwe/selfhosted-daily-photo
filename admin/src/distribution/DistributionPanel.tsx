import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  assignUserDistributionProfile,
  createDistributionProfile,
  deleteDistributionProfile,
  getDistributionAudit,
  getDistributionProfiles,
  getDistributionRollout,
  listUsers,
  testDistributionProfile,
  updateDistributionProfile,
  updateDistributionRollout,
  type AdminUser,
} from "../api";
import { DistributionAudit } from "./DistributionAudit";
import { DistributionProfileEditor } from "./DistributionProfileEditor";
import { DistributionUserAssignments } from "./DistributionUserAssignments";
import {
  emptyDistributionProfile,
  type DistributionAuditItem,
  type DistributionDeploymentPolicy,
  type DistributionProfile,
  type DistributionProfileItem,
  type DistributionRolloutResponse,
  type DistributionTestResult,
} from "./distributionTypes";

type Props = {
  token: string;
};

const emptyPolicy: DistributionDeploymentPolicy = {
  allowInsecureHttp: false,
  privateHostAllowlistConfigured: false,
  manifestMaxBytes: 0,
  apkMaxBytes: 0,
};

export function DistributionPanel({ token }: Props) {
  const [items, setItems] = useState<DistributionProfileItem[]>([]);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audit, setAudit] = useState<DistributionAuditItem[]>([]);
  const [policy, setPolicy] = useState<DistributionDeploymentPolicy>(emptyPolicy);
  const [rollout, setRollout] = useState<DistributionRolloutResponse>();
  const [selectedId, setSelectedId] = useState<number>(0);
  const selectedIdRef = useRef(0);
  const [draft, setDraft] = useState<DistributionProfile>(emptyDistributionProfile());
  const [testResult, setTestResult] = useState<DistributionTestResult>();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const selectedItem = useMemo(
    () => items.find((item) => item.profile.id === selectedId),
    [items, selectedId],
  );

  const refresh = useCallback(async (preferredId?: number) => {
    const [profiles, currentUsers, currentAudit, currentRollout] = await Promise.all([
      getDistributionProfiles(token),
      listUsers(token),
      getDistributionAudit(token),
      getDistributionRollout(token),
    ]);
    setItems(profiles.items);
    setPolicy(profiles.deploymentPolicy);
    setUsers(currentUsers);
    setAudit(currentAudit.items);
    setRollout(currentRollout);
    const nextId = preferredId ?? selectedIdRef.current;
    const selected = profiles.items.find((item) => item.profile.id === nextId)
      || profiles.items.find((item) => item.profile.isDefault)
      || profiles.items[0];
    if (selected) {
      setSelectedId(selected.profile.id);
      selectedIdRef.current = selected.profile.id;
      setDraft({ ...selected.profile });
    } else {
      setSelectedId(0);
      selectedIdRef.current = 0;
      setDraft(emptyDistributionProfile());
    }
  }, [token]);

  useEffect(() => {
    void refresh().catch((error: Error) => setMessage(error.message));
  }, [refresh]);

  function selectProfile(item: DistributionProfileItem) {
    setSelectedId(item.profile.id);
    selectedIdRef.current = item.profile.id;
    setDraft({ ...item.profile });
    setMessage("");
    setTestResult(undefined);
  }

  function startNewProfile() {
    setSelectedId(0);
    selectedIdRef.current = 0;
    setDraft(emptyDistributionProfile());
    setMessage("");
    setTestResult(undefined);
  }

  async function saveProfile() {
    const original = selectedItem?.profile;
    if (
      original?.expectedSigningCertSha256 &&
      original.expectedSigningCertSha256 !== draft.expectedSigningCertSha256 &&
      !window.confirm("Der erwartete Signaturfingerprint wurde geändert. Wirklich speichern?")
    ) return;
    if (draft.sourceMode === "direct" && !window.confirm("Direkten APK-Modus mit den eingetragenen Integritätsdaten speichern?")) return;
    if (!original?.isDefault && draft.isDefault && !window.confirm("Dieses Profil zum neuen Default für alle Nutzer ohne Override machen?")) return;
    setBusy(true);
    setMessage("");
    try {
      const response = draft.id
        ? await updateDistributionProfile(token, draft)
        : await createDistributionProfile(token, draft);
      await refresh(response.profile.id);
      setMessage("Distributionsprofil gespeichert.");
    } catch (error) {
      setMessage((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function removeProfile() {
    if (!draft.id || !window.confirm(`Profil „${draft.name}“ wirklich löschen?`)) return;
    setBusy(true);
    try {
      await deleteDistributionProfile(token, draft.id);
      await refresh();
      setMessage("Profil gelöscht.");
    } catch (error) {
      setMessage((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function runTest() {
    setBusy(true);
    try {
      const response = await testDistributionProfile(token, draft);
      setTestResult(response.result);
      setMessage(response.result.success ? "Quellentest erfolgreich." : "Quellentest fehlgeschlagen.");
    } catch (error) {
      setMessage((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function assign(userId: number, profileId: number | null) {
    setBusy(true);
    try {
      await assignUserDistributionProfile(token, userId, profileId);
      await refresh(draft.id || undefined);
      setMessage("Nutzerzuordnung gespeichert.");
    } catch (error) {
      setMessage((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function saveRollout() {
    if (!rollout) return;
    if (rollout.rollout.enabled && !window.confirm("Automatische Profilumschaltung mit diesen Versionsgrenzen aktivieren?")) return;
    setBusy(true);
    setMessage("");
    try {
      await updateDistributionRollout(token, rollout.rollout);
      await refresh(draft.id || undefined);
      setMessage("Rollout-Automatik gespeichert.");
    } catch (error) {
      setMessage((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const preview = {
        schemaVersion: 1,
        enabled: draft.enabled && draft.sourceMode !== "disabled",
        profileId: draft.id,
        profileUpdatedAt: draft.updatedAt,
        channel: draft.channel,
        projectUrl: draft.projectUrl,
        releaseIndexUrl: draft.releaseIndexUrl,
        releaseHistoryUrl: draft.releaseHistoryUrl,
        releasePageUrl: draft.releasePageUrl,
        directApk: draft.sourceMode === "direct" ? {
          versionName: draft.directApkVersionName,
          versionCode: draft.directApkVersionCode,
          url: draft.directApkUrl,
          sha256: draft.directApkSha256,
          size: draft.directApkSizeBytes,
        } : null,
        expectedPackageName: draft.expectedPackageName,
        expectedSigningCertSha256: draft.expectedSigningCertSha256,
        minSupportedVersionCode: draft.minSupportedVersionCode,
        allowPrerelease: draft.allowPrerelease,
      };

  return (
    <div className="stack distribution-panel">
      <div className="row">
        <div>
          <h2>App-Verteilung</h2>
          <p className="small">Update-, Changelog-, Projekt- und APK-Quellen pro Instanz und Nutzer.</p>
        </div>
        <div className="row">
          <button className="secondary" onClick={startNewProfile}>Neues Profil</button>
          <button disabled={busy} onClick={() => void refresh(draft.id || undefined)}>Aktualisieren</button>
        </div>
      </div>
      {message && <p className="message">{message}</p>}

      {rollout && (
        <section className="stack distribution-preview">
          <div className="row">
            <div>
              <h3>Automatische Bridge-Migration</h3>
              <p className="small">Clients ab Einstiegsversion erhalten das Migrationsprofil. Nach Erreichen der Stable-Version wird nur der automatisch gesetzte Override entfernt.</p>
            </div>
            <button disabled={busy} onClick={() => void saveRollout()}>Automatik speichern</button>
          </div>
          <div className="settings-grid">
            <label>
              <input
                type="checkbox"
                checked={rollout.rollout.enabled}
                onChange={(event) => setRollout({ ...rollout, rollout: { ...rollout.rollout, enabled: event.target.checked } })}
              /> Aktiv
            </label>
            <label>Migrationsprofil
              <select value={rollout.rollout.migrationProfileId} onChange={(event) => setRollout({ ...rollout, rollout: { ...rollout.rollout, migrationProfileId: Number(event.target.value) } })}>
                <option value={0}>Bitte wählen</option>
                {items.map((item) => <option key={item.profile.id} value={item.profile.id}>{item.profile.name}</option>)}
              </select>
            </label>
            <label>Stable-/Defaultprofil
              <select value={rollout.rollout.stableProfileId} onChange={(event) => setRollout({ ...rollout, rollout: { ...rollout.rollout, stableProfileId: Number(event.target.value) } })}>
                <option value={0}>Bitte wählen</option>
                {items.map((item) => <option key={item.profile.id} value={item.profile.id}>{item.profile.name}</option>)}
              </select>
            </label>
            <label>Einstiegs-versionCode
              <input type="number" min={1} value={rollout.rollout.entryVersionCode} onChange={(event) => setRollout({ ...rollout, rollout: { ...rollout.rollout, entryVersionCode: Number(event.target.value) } })} />
            </label>
            <label>Stable erreicht ab versionCode
              <input type="number" min={1} value={rollout.rollout.stableVersionCode} onChange={(event) => setRollout({ ...rollout, rollout: { ...rollout.rollout, stableVersionCode: Number(event.target.value) } })} />
            </label>
          </div>
          <div className="grid4">
            <article className="stat"><h3>Unbekannt</h3><p>{rollout.summary.unknown || 0}</p></article>
            <article className="stat"><h3>Migration</h3><p>{rollout.summary.migration || 0}</p></article>
            <article className="stat"><h3>Stable erreicht</h3><p>{rollout.summary.stable || 0}</p></article>
            <article className="stat"><h3>Manuell</h3><p>{rollout.summary.manual_override || 0}</p></article>
          </div>
          {rollout.clients.length > 0 && (
            <table>
              <thead><tr><th>Nutzer-ID</th><th>App</th><th>versionCode</th><th>Phase</th><th>Zuletzt gesehen</th></tr></thead>
              <tbody>{rollout.clients.map((client) => <tr key={client.userId}><td>{client.userId}</td><td>{client.versionName || "-"}</td><td>{client.versionCode}</td><td>{client.phase}</td><td>{client.lastSeenAt}</td></tr>)}</tbody>
            </table>
          )}
        </section>
      )}

      <div className="distribution-layout">
        <aside className="distribution-profile-list">
          {items.map((item) => (
            <button
              key={item.profile.id}
              className={`distribution-profile-button ${item.profile.id === selectedId ? "active" : ""}`}
              onClick={() => selectProfile(item)}
            >
              <strong>{item.profile.name}</strong>
              <span>{item.profile.isDefault ? "Default · " : ""}{item.profile.enabled ? "aktiv" : "deaktiviert"}</span>
              <span>{item.profile.sourceMode} · {item.profile.channel}</span>
              <span>{item.assignedUserCount} Nutzer-Override(s)</span>
            </button>
          ))}
        </aside>
        <div className="stack">
          <DistributionProfileEditor
            profile={draft}
            originalProfile={selectedItem?.profile}
            assignedUserCount={selectedItem?.assignedUserCount || 0}
            policy={policy}
            testResult={testResult}
            busy={busy}
            onChange={(profile) => { setDraft(profile); setTestResult(undefined); }}
            onSave={() => void saveProfile()}
            onDelete={() => void removeProfile()}
            onTest={() => void runTest()}
          />
          <section className="stack distribution-preview">
            <h3>Effektive Clientantwort</h3>
            <pre>{JSON.stringify(preview, null, 2)}</pre>
          </section>
        </div>
      </div>

      <DistributionUserAssignments users={users} profiles={items} busy={busy} onAssign={(userId, profileId) => void assign(userId, profileId)} />
      <DistributionAudit items={audit} />
    </div>
  );
}
