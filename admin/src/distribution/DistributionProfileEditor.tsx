import type {
  DistributionDeploymentPolicy,
  DistributionProfile,
  DistributionTestResult,
} from "./distributionTypes";

type Props = {
  profile: DistributionProfile;
  originalProfile?: DistributionProfile;
  assignedUserCount: number;
  policy: DistributionDeploymentPolicy;
  testResult?: DistributionTestResult;
  busy: boolean;
  onChange: (profile: DistributionProfile) => void;
  onSave: () => void;
  onDelete: () => void;
  onTest: () => void;
};

const officialFingerprint =
  "72e05a43a7be5837d83c922ad3496782499547fd94a5efa431dec712df6d4138";

function optionalNumber(value: string): number | null {
  const clean = value.trim();
  if (!clean) return null;
  const parsed = Number(clean);
  return Number.isFinite(parsed) ? parsed : null;
}

export function DistributionProfileEditor({
  profile,
  originalProfile,
  assignedUserCount,
  policy,
  testResult,
  busy,
  onChange,
  onSave,
  onDelete,
  onTest,
}: Props) {
  const update = <K extends keyof DistributionProfile>(
    key: K,
    value: DistributionProfile[K],
  ) => onChange({ ...profile, [key]: value });
  const signatureChanged = Boolean(
    originalProfile?.expectedSigningCertSha256 &&
      originalProfile.expectedSigningCertSha256 !==
        profile.expectedSigningCertSha256,
  );
  const integrityMissing =
    !profile.expectedSigningCertSha256 ||
    (profile.sourceMode === "direct" &&
      (!profile.directApkSha256 || profile.directApkVersionCode < 1));

  function applyPreset(preset: "harzcloud" | "forgejo" | "github" | "static" | "direct" | "disabled") {
    const base = { ...profile, enabled: true, isDefault: profile.isDefault };
    if (preset === "harzcloud") {
      onChange({
        ...base,
        name: "Harzcloud Stable",
        sourceMode: "manifest",
        channel: "stable",
        projectUrl: "https://code.harzcloud.de/daily-harzcloud/daily",
        releaseIndexUrl: "https://releases.daily.harzcloud.de/index.json",
        releaseHistoryUrl: "",
        releasePageUrl:
          "https://code.harzcloud.de/daily-harzcloud/daily/releases",
        expectedPackageName: "com.selfhosted.daily",
        expectedSigningCertSha256: officialFingerprint,
      });
      return;
    }
    if (preset === "direct") {
      onChange({ ...base, sourceMode: "direct", releaseIndexUrl: "" });
      return;
    }
    if (preset === "disabled") {
      onChange({ ...base, enabled: false, isDefault: false, sourceMode: "disabled" });
      return;
    }
    const examples = {
      forgejo: {
        projectUrl: "https://forge.example.org/owner/daily",
        releaseIndexUrl: "https://downloads.example.org/daily/index.json",
        releasePageUrl: "https://forge.example.org/owner/daily/releases",
      },
      github: {
        projectUrl: "https://github.com/ORG/REPO",
        releaseIndexUrl: "https://downloads.example.org/daily/index.json",
        releasePageUrl: "https://github.com/ORG/REPO/releases",
      },
      static: {
        projectUrl: "https://example.org/daily",
        releaseIndexUrl: "https://example.org/daily/releases/index.json",
        releasePageUrl: "https://example.org/daily/releases/",
      },
    } as const;
    onChange({
      ...base,
      sourceMode: "manifest",
      releaseHistoryUrl: "",
      ...examples[preset],
    });
  }

  return (
    <section className="distribution-editor stack">
      <div className="row">
        <div>
          <h3>{profile.id ? `Profil #${profile.id}` : "Neues Profil"}</h3>
          <p className="small">
            Provider-neutrale Quelle für Updates, Changelog und Projektlinks.
          </p>
        </div>
        <div className="row">
          <button disabled={busy} onClick={onSave} className="accent">
            Speichern
          </button>
          {profile.id > 0 && (
            <button disabled={busy} onClick={onTest}>
              Quelle testen
            </button>
          )}
          {profile.id > 0 && (
            <button
              disabled={busy || profile.isDefault || assignedUserCount > 0}
              onClick={onDelete}
              className="danger"
            >
              Löschen
            </button>
          )}
        </div>
      </div>

      <div className="distribution-presets row">
        <span className="small">Vorlagen:</span>
        <button className="secondary compact" onClick={() => applyPreset("harzcloud")}>Harzcloud</button>
        <button className="secondary compact" onClick={() => applyPreset("forgejo")}>Forgejo/Gitea</button>
        <button className="secondary compact" onClick={() => applyPreset("github")}>GitHub + neutraler Index</button>
        <button className="secondary compact" onClick={() => applyPreset("static")}>Statischer Index</button>
        <button className="secondary compact" onClick={() => applyPreset("direct")}>Direkte APK</button>
        <button className="secondary compact" onClick={() => applyPreset("disabled")}>Deaktiviert</button>
      </div>

      {(signatureChanged || profile.sourceMode === "direct" || integrityMissing || profile.isDefault || assignedUserCount > 0 || policy.allowInsecureHttp || policy.privateHostAllowlistConfigured) && (
        <div className="distribution-warnings">
          {signatureChanged && <p>Warnung: Der erwartete Signaturfingerprint wurde verändert.</p>}
          {profile.sourceMode === "direct" && <p>Direkter APK-Modus: URL und Metadaten müssen immutable sein.</p>}
          {integrityMissing && <p>Integritätsangaben sind unvollständig.</p>}
          {profile.isDefault && !profile.enabled && <p>Ein Default-Profil darf nicht deaktiviert sein.</p>}
          {profile.isDefault && <p>Das Default-Profil kann nicht gelÃ¶scht werden. Zuerst ein Ersatzprofil als Default setzen.</p>}
          {assignedUserCount > 0 && <p>Dieses Profil ist {assignedUserCount} Nutzer(n) zugeordnet.</p>}
          {policy.allowInsecureHttp && <p>Deployment erlaubt derzeit unsichere HTTP-Verteilungsziele.</p>}
          {policy.privateHostAllowlistConfigured && <p>Deployment enthält eine private Host-/CIDR-Allowlist.</p>}
        </div>
      )}

      <div className="grid2">
        <label>
          Interner Profilname
          <input value={profile.name} maxLength={120} onChange={(event) => update("name", event.target.value)} />
        </label>
        <label>
          Kanal
          <input value={profile.channel} maxLength={40} onChange={(event) => update("channel", event.target.value)} />
        </label>
        <label>
          Modus
          <select value={profile.sourceMode} onChange={(event) => update("sourceMode", event.target.value as DistributionProfile["sourceMode"])}>
            <option value="manifest">Manifest</option>
            <option value="direct">Direkte APK</option>
            <option value="disabled">Deaktiviert</option>
          </select>
        </label>
        <div className="row distribution-checks">
          <label className="checkbox">
            <input type="checkbox" checked={profile.enabled} disabled={profile.sourceMode === "disabled"} onChange={(event) => update("enabled", event.target.checked)} />
            Aktiviert
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={profile.isDefault} disabled={profile.sourceMode === "disabled"} onChange={(event) => update("isDefault", event.target.checked)} />
            Default-Profil
          </label>
          <label className="checkbox">
            <input type="checkbox" checked={profile.allowPrerelease} onChange={(event) => update("allowPrerelease", event.target.checked)} />
            Vorabversionen erlauben
          </label>
        </div>
      </div>

      <div className="grid2">
        <label>Projekt-/Quellcodeseite<input value={profile.projectUrl} onChange={(event) => update("projectUrl", event.target.value)} /></label>
        <label>Release-Seite<input value={profile.releasePageUrl} onChange={(event) => update("releasePageUrl", event.target.value)} /></label>
        <label>Release-Index / Manifest<input value={profile.releaseIndexUrl} disabled={profile.sourceMode !== "manifest"} onChange={(event) => update("releaseIndexUrl", event.target.value)} /></label>
        <label>Separate History-URL (optional)<input value={profile.releaseHistoryUrl} disabled={profile.sourceMode !== "manifest"} onChange={(event) => update("releaseHistoryUrl", event.target.value)} /></label>
      </div>

      {profile.sourceMode === "direct" && (
        <div className="grid2 distribution-direct-fields">
          <label>APK-URL<input value={profile.directApkUrl} onChange={(event) => update("directApkUrl", event.target.value)} /></label>
          <label>VersionName<input value={profile.directApkVersionName} onChange={(event) => update("directApkVersionName", event.target.value)} /></label>
          <label>VersionCode<input type="number" min={1} value={profile.directApkVersionCode || ""} onChange={(event) => update("directApkVersionCode", Number(event.target.value || 0))} /></label>
          <label>Größe in Bytes (optional)<input type="number" min={1} value={profile.directApkSizeBytes ?? ""} onChange={(event) => update("directApkSizeBytes", optionalNumber(event.target.value))} /></label>
          <label className="distribution-wide">APK SHA-256<input value={profile.directApkSha256} maxLength={64} onChange={(event) => update("directApkSha256", event.target.value.toLowerCase())} /></label>
        </div>
      )}

      <div className="grid2">
        <label>Paketname<input value={profile.expectedPackageName} maxLength={200} onChange={(event) => update("expectedPackageName", event.target.value)} /></label>
        <label>Minimal unterstützter VersionCode<input type="number" min={1} value={profile.minSupportedVersionCode ?? ""} onChange={(event) => update("minSupportedVersionCode", optionalNumber(event.target.value))} /></label>
        <label className="distribution-wide">Signaturzertifikat SHA-256<input value={profile.expectedSigningCertSha256} maxLength={64} onChange={(event) => update("expectedSigningCertSha256", event.target.value.toLowerCase())} /></label>
      </div>

      {testResult && (
        <div className={`distribution-test-result ${testResult.success ? "ok" : "failed"}`}>
          <strong>{testResult.success ? "Test erfolgreich" : "Test fehlgeschlagen"}</strong>
          <span>Host: {testResult.finalHost || "–"}</span>
          <span>Status: {testResult.httpStatusClass || "–"}</span>
          <span>Latenz: {testResult.latencyMs} ms</span>
          <span>Schema: {testResult.schemaVersion || "–"}</span>
          <span>Release: {testResult.detectedVersion || "–"}</span>
          {testResult.errorClass && <span>Fehlerklasse: {testResult.errorClass}</span>}
          {testResult.warnings?.map((warning) => <span key={warning}>Hinweis: {warning}</span>)}
        </div>
      )}
    </section>
  );
}
