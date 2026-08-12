# App-Verteilung und sichere Android-Updates

Daily verwaltet Projektlinks, Release-History und Android-Updates als providerneutrale Distributionsprofile. Forgejo, Gitea, GitHub oder ein statischer Webserver sind nur mögliche Hosts; die gespeicherten Daten und die Android-App kennen keine Provider-API.

## Vertrauensmodell

- Das authentifizierte Backend liefert dem angemeldeten Nutzer ausschließlich sein effektives, bereits gespeichertes Profil über `GET /api/app-distribution`.
- Nur der Admin-Test führt serverseitige Netzwerkzugriffe auf ein Manifest oder eine Direct-APK aus.
- Android lädt Manifeste und APKs direkt von den im Profil hinterlegten Zielen. Das Backend ist kein Downloadproxy.
- Die installierte App-Signatur ist die entscheidende Updateidentität. Ein Profilfingerprint kann diese Prüfung nur verschärfen, niemals ersetzen.
- Ungeprüfte APKs liegen ausschließlich im privaten App-Cache und werden nicht im öffentlichen Downloadordner gespeichert.

## Profile und Nutzerzuordnung

Im Adminbereich `Konfiguration -> App-Verteilung` lassen sich Profile anlegen, testen, aktivieren und Nutzern zuweisen.

- Genau ein aktiviertes Profil ist Default.
- `distribution_profile_id = NULL` bedeutet „Default verwenden“.
- Ein Default-Profil und ein noch zugeordnetes Profil können nicht gelöscht werden.
- Default-Wechsel und Zuordnungen werden auditiert.
- Audit-Snapshots redigieren Querywerte; Tokens, Cookies, Authorization-Header und fremde Antwortkörper werden nicht gespeichert.
- Profile starten mit Revision 1. Updates senden `expectedRevision`; ein veralteter Stand erhält HTTP 409 samt aktueller Revision und Serverfassung.
- SQLite-Trigger verhindern UPDATE und DELETE auf Auditzeilen. Das ist ein lokaler Append-only-Schutz, keine kryptografische Versiegelung.
- JSON-Payloads der schreibenden und testenden Distribution-Adminrouten sind auf 64 KiB begrenzt.

Die Modi sind:

- `manifest`: providerneutraler JSON-Index; optional getrennte History-URL.
- `direct`: eine explizite APK mit VersionName, VersionCode, SHA-256 und optionaler Größe.
- `disabled`: Updateprüfung und Releaseabruf sind für dieses Profil abgeschaltet.

## Client-API

Beispiel einer Manifest-Antwort:

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "profileId": 12,
  "profileUpdatedAt": "2026-08-11T10:00:00Z",
  "channel": "stable",
  "projectUrl": "https://code.example.org/team/daily",
  "releaseIndexUrl": "https://downloads.example.org/daily/index.json",
  "releaseHistoryUrl": "",
  "releasePageUrl": "https://code.example.org/team/daily/releases",
  "directApk": null,
  "expectedPackageName": "com.selfhosted.daily",
  "expectedSigningCertSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "minSupportedVersionCode": null,
  "allowPrerelease": false
}
```

Die Antwort ist privat cachebar und besitzt einen ETag aus Nutzerzuordnung und Profilstand. Der Endpunkt macht keinen externen Abruf. Authentifizierte Android-Clients melden dabei ihre installierte `versionName` und ihren `versionCode` als Queryparameter. Fuer Rollout-Entscheidungen wird ausschliesslich der numerische VersionCode verwendet; der letzte Bericht wird nutzerbezogen in `distribution_client_states` gespeichert.

## Automatische Bridge-Migration

Im Adminbereich kann eine Singleton-Rollout-Policy aus Einstiegsversion, Stable-Zielversion, Migrationsprofil und aktiviertem Defaultprofil konfiguriert werden.

- Unterhalb der Einstiegsversion wird der Client nur beobachtet und nicht neu zugeordnet.
- Ab Einstiegsversion bis vor das Stable-Ziel erhaelt er das Migrationsprofil, sofern kein anderes manuelles Override besteht.
- Sobald das Stable-Ziel gemeldet wird, entfernt das Backend nur ein exakt von der Automatik verwendetes Migrationsprofil. Danach erbt der Nutzer wieder das Defaultprofil.
- Wiederholte Abrufe sind idempotent; echte Wechsel laufen in einer Transaktion und landen im unveraenderbaren Distributions-Audit.

Bei einer zweistufigen oeffentlichen Migration bleibt der Legacy-Index zunaechst auf der Bridge-Version, waehrend das Migrationsprofil bereits auf die naechste Harzcloud-Version zeigt. So koennen alte Clients die Kompatibilitaets-Bridge nicht ueberspringen. Der oeffentliche Stable-Zeiger wird erst nach der gewuenschten Migration auf den Nachfolger gesetzt.

Adminrouten:

- `GET|POST /api/admin/distribution/profiles`
- `PUT|DELETE /api/admin/distribution/profiles/:id`
- `POST /api/admin/distribution/profiles/:id/test`
- `POST /api/admin/distribution/test` testet den sichtbaren, noch nicht gespeicherten Entwurf
- `GET /api/admin/distribution/audit`
- `GET|PUT /api/admin/distribution/rollout`
- `PUT /api/admin/users/:id/distribution-profile`

## Providerneutraler Release-Index

`release_history_url` darf leer bleiben. Dann dient derselbe Index mit seinem `releases`-Array zugleich als Update- und Historyquelle.

```json
{
  "schemaVersion": 1,
  "latest": "0.8.29",
  "releases": [
    {
      "version": "0.8.29",
      "versionCode": 142029,
      "packageName": "com.selfhosted.daily",
      "signingCertSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "apkUrl": "https://downloads.example.org/daily/v0.8.29/app-release.apk",
      "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
      "size": 14000000,
      "releaseUrl": "https://code.example.org/team/daily/releases/tag/v0.8.29",
      "releasedAt": "2026-08-11T10:00:00Z",
      "title": "Daily 0.8.29",
      "highlights": ["Providerneutrale App-Verteilung"],
      "details": []
    }
  ]
}
```

Die bestehenden Felder `sha256` und `size` bleiben unverändert. Historische Einträge ohne Installationsmetadaten bleiben als Changelog sichtbar, werden aber nicht als installierbares Update angeboten. Die vorübergehende Altindex-Ausnahme gilt ausschließlich für APK-URLs auf `releases.daily.harzcloud.de`; Paket und Zertifikat werden auch dort gegen Profil und installierte App geprüft.

Der Indexgenerator verlangt Installationsidentität, Kanal, Prerelease-Status und sämtliche providerbezogenen URL-Templates explizit. Er sortiert deterministisch nach `versionCode`; SemVer-Suffixe wie `1.0.0-beta.1` werden nicht numerisch zerlegt:

```text
python scripts/build_release_index.py \
  --version 0.8.29 \
  --version-code 142029 \
  --channel stable \
  --prerelease false \
  --package-name com.selfhosted.daily \
  --signing-cert-sha256 <PUBLIC_CERT_SHA256> \
  --apk-sha256 <APK_SHA256> \
  --apk-size <APK_SIZE_BYTES> \
  --apk-url-template 'https://downloads.example.org/daily/{tag}/app-release.apk' \
  --changelog-url-template 'https://downloads.example.org/daily/{tag}/changelog.json' \
  --release-url-template 'https://code.example.org/team/daily/releases/tag/{tag}' \
  --apk dist/app-release.apk \
  --notes .github/release-notes/v0.8.29.json \
  --output dist/index.json
```

Der Fingerprint ist ein öffentlicher Zertifikatswert, kein Signiersecret. Er muss aus der fertig signierten APK ermittelt werden.

Die Forgejo-Candidate-Pipeline trägt die offiziellen Harzcloud-Templates ausdrücklich nur als Candidate-Metadaten in ihrer Provenienz. Sie ruft `build_release_index.py` nicht auf und erzeugt absichtlich keinen veröffentlichbaren Index, weil ihr APK-Artefakt unsigniert ist. Im aktuellen Forgejo-Bestand existiert somit noch kein integrierter signierter Index-Publisher. Der spätere, separat freizugebende Signing-/Release-Workflow muss nach der Signaturprüfung alle verpflichtenden Generatorparameter (`version`, `versionCode`, Kanal, Prerelease-Status, Paketname, öffentlicher Zertifikatfingerprint, APK-Hash und -Größe sowie alle drei URL-Templates) explizit übergeben und den externen Index zuletzt atomar publizieren.

## URL- und Netzwerkgrenzen

Standardwerte:

```env
ALLOW_INSECURE_DISTRIBUTION_URLS=false
DISTRIBUTION_PRIVATE_HOST_ALLOWLIST=
DISTRIBUTION_MANIFEST_MAX_BYTES=1048576
DISTRIBUTION_APK_MAX_BYTES=262144000
```

- HTTP wird nur durch die deploymentseitige Variable freigegeben.
- Private Ziele benötigen zusätzlich eine exakte Host- oder CIDR-Allowlist.
- Der Admin-Test prüft alle DNS-Antworten, blockiert private/Loopback/Link-local/Multicast-/unspecified Ziele, validiert jeden Redirect und verhindert HTTPS-Downgrades.
- Proxy-Umgebungsvariablen werden für den Testclient nicht übernommen.
- Direct-APK-Tests verwenden HEAD oder einen begrenzten Range-Abruf, nicht die vollständige APK.
- Distributions-URLs müssen stabil, öffentlich abrufbar und vollständig query-frei sein. URL-Userinfo und Credentials in URLs werden nicht unterstützt.
- Private Artefakte benötigen künftig einen separat entworfenen Authentifizierungsmechanismus und sind nicht Teil dieser ersten providerneutralen Version.

Android deaktiviert automatische Redirects für Index, History und APK. Ein unveränderter Origin besteht aus Schema, kanonischem Hostnamen und effektivem Port. Ein ausdrücklich vom Backend konfigurierter Selfhosting-Origin darf selbst lokal sein; diese Freigabe geht nicht auf einen anderen Port oder Host über. Als einzige Schema-/Port-Ausnahme darf derselbe explizit konfigurierte Host von HTTP auf HTTPS und dabei ausschließlich vom Standardport 80 auf 443 aufgewertet werden. HTTPS→HTTP und beliebige Portwechsel bleiben gesperrt. Eine Manifest-APK ist im Gegensatz zu einer administrativ konfigurierten Direct-APK niemals ein explizit vertrautes Ausgangsziel. Jeder Redirect wird erneut geprüft und nach drei Schritten beendet.

Die App koppelt die Policyprüfung an den tatsächlichen Verbindungsaufbau: Die bei der URL-Prüfung erhaltene und normalisierte Adressliste wird einem dedizierten OkHttp-`Dns` übergeben. Dieser liefert nur für exakt den erwarteten Host genau diese Liste und lehnt zusätzliche Hostauflösungen ab. Pro Index-, History-, APK- und Redirectrequest wird ein isolierter `ConnectionPool` verwendet, sodass keine frühere ungeprüfte Verbindung wiederverwendet werden kann. `Proxy.NO_PROXY` verhindert eine Umgehung des Ziel-Pinnings durch Systemproxy-Einstellungen. URL, TLS-SNI und HTTP-Host bleiben auf dem geprüften Originalhost; IPv4-mapped IPv6 wird vor der Netzklassifizierung auf IPv4 normalisiert.

## Android-Auflösung und Cache

Auflösungsreihenfolge:

1. aktuelle Backendkonfiguration,
2. höchstens sieben Tage alte Last-known-good-Konfiguration für exakt diesen Server und Nutzer,
3. Build-Fallback nur für die bekannte offizielle Instanz,
4. sichere Deaktivierung der Updateprüfung.

Scheitert der Backendabruf und Token, Nutzer-ID oder API-Origin wurden währenddessen gelöscht oder verändert, endet die Auflösung sofort. LKG wird nur bei temporären Transport-/Serverfehlern und unveränderter Sitzung verwendet.

Konfiguration und Release-Cache sind nach normalisierter API-Origin, Nutzer-ID, Profil-ID, Kanal und `profileUpdatedAt` getrennt. Der alte globale `github_release_history_*`-Cache wird bewusst ignoriert. Offline-Modus und ein Worker ohne angemeldeten Nutzer erzeugen keine Distribution-, Manifest-, Release- oder Changeloganfragen.

## APK-Prüfkette

Nach einer bewussten Nutzerbestätigung zeigt Daily Version und Zielhost und führt dann aus:

1. höchstens drei validierte Redirects, kein HTTPS-Downgrade,
2. privater Streaming-Download mit festem Größenlimit,
3. SHA-256 während des Streams und optionale exakte Größenprüfung,
4. Paketname gegen Release, Profil und installierte App,
5. tatsächlicher VersionName gegen Release,
6. tatsächlicher VersionCode gegen Release und größer als die installierte Version,
7. APK-Signatur exakt gegen die installierte Signatur,
8. zusätzliche Prüfung gegen Release- und Profilfingerprint,
9. atomare Finalisierung und Übergabe per `FileProvider` an den Android Package Installer.

Bei jedem Fehler wird die temporäre Datei gelöscht. Es gibt weder automatischen Download noch stille Installation.

Die Updateauswahl filtert zuerst Kanal (leer bedeutet `stable`) und Prerelease-Freigabe und wählt danach den höchsten `versionCode`, unabhängig vom globalen Root-`latest`. `minSupportedVersionCode` kennzeichnet ein Update nur dann als erforderlich, wenn ein vollständiger, installierbarer Kandidat mindestens diesen Code erreicht. Auch dann bleiben Download und Installer an eine sichtbare Nutzerbestätigung gebunden; ohne verifizierbaren Kandidaten wird die App nicht gesperrt.

Einladungslinks verwenden zuerst die effektive Release-/Projekt-URL des Nutzerprofils, danach `PUBLIC_DOWNLOAD_URL` beziehungsweise `PUBLIC_PROJECT_URL` aus `/api/health` und zuletzt eine sichere `/#download`-Ableitung aus der API-Origin. Offline wird ausschließlich eine LKG-Konfiguration derselben Origin und Nutzeridentität verwendet.

## Rollout und Rücknahme

1. Backend/Admin zuerst ausrollen und Default-Profil prüfen.
2. Mit einem Testnutzer Profilauflösung, History und APK-Test abnehmen.
3. Erst danach eine mit der bestehenden Produktionsidentität signierte Bridge-Version veröffentlichen.
4. Den externen Index zuletzt atomar aktualisieren.
5. Bei einem Profilfehler das Profil korrigieren oder ein deaktiviertes Nutzerprofil zuweisen; App-Kernfunktionen bleiben unabhängig.
6. Bei einem Artefaktfehler den Index auf den letzten gültigen Eintrag zurücksetzen. Keine abweichend signierte APK freigeben.
