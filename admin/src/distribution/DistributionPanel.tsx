import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  assignUserDistributionProfile,
  createDistributionProfile,
  deleteDistributionProfile,
  getDistributionAudit,
  getDistributionProfiles,
  listUsers,
  testDistributionProfile,
  updateDistributionProfile,
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
  const [selectedId, setSelectedId] = useState<number>(0);
  const selectedIdRef = useRef(0);
  const [draft, setDraft] = useState<DistributionProfile>(emptyDistributionProfile());
  const [testResults, setTestResults] = useState<Record<number, DistributionTestResult>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  const selectedItem = useMemo(
    () => items.find((item) => item.profile.id === selectedId),
    [items, selectedId],
  );

  const refresh = useCallback(async (preferredId?: number) => {
    const [profiles, currentUsers, currentAudit] = await Promise.all([
      getDistributionProfiles(token),
      listUsers(token),
      getDistributionAudit(token),
    ]);
    setItems(profiles.items);
    setPolicy(profiles.deploymentPolicy);
    setUsers(currentUsers);
    setAudit(currentAudit.items);
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
  }

  function startNewProfile() {
    setSelectedId(0);
    selectedIdRef.current = 0;
    setDraft(emptyDistributionProfile());
    setMessage("");
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
    if (!draft.id) return;
    setBusy(true);
    try {
      const response = await testDistributionProfile(token, draft.id);
      setTestResults((current) => ({ ...current, [draft.id]: response.result }));
      await refresh(draft.id);
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
            testResult={draft.id ? testResults[draft.id] : undefined}
            busy={busy}
            onChange={setDraft}
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
