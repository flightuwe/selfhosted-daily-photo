# Daily

![Daily Logo](assets/daily-logo.png)

Private, selfhosted Daily-Moment App (Android + Admin Web + Go Backend) fuer kleine Gruppen.

## Inhalt
- Sichtbarer Tagespost entsperrt den heutigen Feed
- Ausserhalb des Daily-Fensters postest du ein `Extra`
- Im aktiven Daily-Fenster gibt es genau ein echtes `Daily-Moment`
- Time Capsules bleiben bis zum Unlock fuer alle verborgen
- Kalender, Chat, Sondermoment und Admin-Panel fuer kleine Gruppen
- Vollstaendig selfhosted auf Synology/Portainer moeglich

## Produktlogik kurz erklaert
- `Extra`: normaler sichtbarer Post ausserhalb des aktiven Daily-Fensters
- `Daily-Moment`: der echte Tagesmoment im aktiven Daily-Fenster
- `Time Capsule`: bewusst gesperrter Post, der erst zum Unlock sichtbar wird
- Der heutige Feed entsperrt sich erst, wenn du heute einen sichtbaren Post gesetzt hast
- Eine private oder noch gesperrte Capsule entsperrt den heutigen Feed nicht

## Architektur
- `backend/` Go API + SQLite + Upload-Speicher
- `admin/` React/Vite Admin-Panel
- `android/` Kotlin/Compose App
- `deploy/` Compose/Portainer/Nginx Vorlagen

Datenhaltung:
- DB: `backend-data/app.db`
- Bilder: `backend-data/uploads/*`

## Quick Start (Synology + Portainer)

### 1. Accounts und Dienste, die du brauchst
Pflicht:
- GitHub Account (Repo + Actions)
- Synology mit Docker/Portainer
- Eigene Domain/Subdomain (z. B. `daily.deine-domain.tld`)

Fuer echte Push-Benachrichtigungen (FCM) zusaetzlich:
- Firebase Account
- Firebase Projekt
- Android App in Firebase mit Paketname `com.selfhosted.daily`

### 2. Ordner auf Synology anlegen
Empfohlen:
- `/volume1/docker/selfhosted-daily-photo/backend-data`
- `/volume1/docker/selfhosted-daily-photo/nginx`
- `/volume1/docker/selfhosted-daily-photo/secrets`

Datei anlegen:
- `/volume1/docker/selfhosted-daily-photo/nginx/default.conf`
  Inhalt aus: `deploy/synology/nginx-default.conf`

### 3. Firebase Service Account (nur fuer echte Push)
- Firebase Console -> Project Settings -> Service Accounts -> neuen JSON Key erstellen
- Datei speichern als:
  `/volume1/docker/selfhosted-daily-photo/secrets/firebase-service-account.json`

### 4. Portainer Stack deployen
- In Portainer: `Stacks` -> `Add stack`
- Inhalt von `deploy/portainer-stack.yml` einfuegen
- Mindestens diese Werte ersetzen:
  - `JWT_SECRET`
  - `BOOTSTRAP_ADMIN_PASSWORD`
  - `PUBLIC_BASE_URL`
  - `CORS_ORIGINS`
  - `FCM_PROJECT_ID` (wenn FCM aktiv)
- Deploy

### 5. Synology Reverse Proxy
Empfohlen:
- Source: `https://daily.deine-domain.tld`
- Destination: `http://127.0.0.1:13379`

Dann erreichbar:
- Admin Web: `https://daily.deine-domain.tld`
- API: `https://daily.deine-domain.tld/api/health`

## GitHub Setup (CI/CD)

### Workflows
- `CI`: Backend-Tests, Admin-Build und Android-Debug-Build fuer Pushes auf `main` und Pull Requests
- `Publish Server Images`: baut und pusht Backend- und Admin-Images nach GHCR bei Push auf `main`
- `Release Android APK`: baut die signierte APK bei semantischen Tags `v*` und erstellt das GitHub Release

### Server-Image Tags
Beim Push auf `main`:
- `ghcr.io/flightuwe/daily-backend:latest`
- `ghcr.io/flightuwe/daily-backend:sha-<shortsha>`
- `ghcr.io/flightuwe/daily-backend:srv-<run>.<attempt>`

Wichtig fuer App-Anzeige:
- Wenn im Stack `APP_VERSION=dev` bleibt, zeigt der Server trotzdem die Build-Version `srv-...` an.
- So kannst du in der App unter Profil sofort sehen, welche Server-Version wirklich laeuft.

## GitHub Secrets (vollstaendig)

### Fuer Android Release (Pflicht)
Diese 5 Secrets muessen gesetzt sein:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`

### Fuer GHCR Pull auf Synology (nur wenn Images privat)
- In Portainer Registry hinterlegen (GitHub PAT mit `read:packages`)
- Name/Passwort dann in Portainer, nicht zwingend als GitHub Secret

### Fuer den laufenden Betrieb
- Laufende Backend-/Push-Konfiguration liegt nicht in GitHub Actions, sondern in `deploy/` und den Umgebungsvariablen des Servers
- Relevante Betriebswerte wie `JWT_SECRET`, `PUBLIC_BASE_URL`, `FCM_PROJECT_ID` und `APP_VERSION` werden auf dem Zielsystem gesetzt
- GitHub Actions braucht nur die Secrets, die wirklich fuer Release oder Registry-Zugriff noetig sind

## Android Build/Release

### Neue App-Version releasen
1. `android/app/build.gradle.kts`:
   - `versionCode` erhoehen
   - `versionName` erhoehen
2. Commit auf `main`
3. Tag pushen:
   - `git tag vX.Y.Z`
   - `git push origin vX.Y.Z`
4. APK liegt danach im GitHub Release als `app-release.apk`
5. Changelog wird automatisch erzeugt:
   - Release-Body aus `.github/release-notes/vX.Y.Z.md`
   - Asset `changelog.json` fuer die App
6. Wenn keine manuellen Release-Notes vorliegen, faellt der Workflow auf deduplizierte Commit-Highlights zurueck

Hinweis:
- Der Android-Release-Workflow ist absichtlich **tag-only** und akzeptiert nur semantische Tags `vX.Y.Z`.
- Manuelle Release-Notes bestehen immer aus einem Paar:
  - `.github/release-notes/vX.Y.Z.md`
  - `.github/release-notes/vX.Y.Z.json`
- Details zur Policy stehen in `.github/release-notes/README.md`

## E2E Testen vor Release
- Vollstaendige Checkliste:
  - `docs/testing/RELEASE_E2E_CHECKLIST.md`
- Smoke-Skript:
  - `pwsh scripts/test-e2e-smoke.ps1 -BaseUrl https://daily.teacloud.synology.me -AdminToken <token>`
- Feed-Burst-Skript (Rate-Limit/5xx-Guard):
  - `pwsh scripts/test-feed-rate-limit.ps1 -BaseUrl https://daily.teacloud.synology.me -UserToken <token> -Requests 80 -Concurrency 8`
- Interner Daily-Test-Trigger (ohne Broadcast an alle):
  - silent: `pwsh scripts/trigger-daily-test.ps1 -AdminToken <adminToken> -Silent`
  - nur Testnutzer pushen: `pwsh scripts/trigger-daily-test.ps1 -AdminToken <adminToken> -NotifyUserIds "12,15"`

## Vollumzug auf neuen Server
- Playbook: `docs/server-migration-playbook.md`
- Backup-Skript (PowerShell): `scripts/backup-full.ps1`
- Restore-Skript (PowerShell): `scripts/restore-full.ps1`
- Smoke-Validierung (PowerShell): `scripts/validate-stack.ps1`
- Linux-Varianten:
  - `scripts/backup-full.sh`
  - `scripts/restore-full.sh`
  - `scripts/validate-stack.sh`

## Erste Inbetriebnahme testen
1. `https://daily.deine-domain.tld/api/health` -> `ok: true`
2. Admin Login mit Bootstrap-Admin
3. Testnutzer anlegen
4. Android App installieren und einloggen
5. Ausserhalb eines Daily-Fensters ein sichtbares `Extra` posten und pruefen, dass der heutige Feed danach entsperrt ist
6. Danach ein Daily-Fenster manuell triggern und im aktiven Zeitraum ein echtes `Daily-Moment` posten
7. Optional eine `Time Capsule` anlegen und pruefen, dass sie vor dem Unlock noch nicht im heutigen Feed auftaucht

## Updates ohne Portainer-Klickorgie
Option A (manuell, schnell):
- In Portainer Stack: `Pull latest image` + `Redeploy`

Option B (empfohlen):
- GitHub Actions Deploy via SSH auf Synology (automatisches `docker compose pull && up -d`)
- Dann musst du nicht jedes Mal Portainer oeffnen

## Debugging
- Debug-UI (Dozzle) im Stack enthalten
- Aktivieren per Compose Profile:
  - `COMPOSE_PROFILES=debug`
- Log-UI dann unter:
  - `http://<synology-ip>:13380`
- Details: `deploy/DEBUGGING.md`

## Wichtige Umgebungsvariablen (Backend)
Siehe `backend/.env.example`:
- `APP_ADDRESS`
- `DB_PATH`
- `UPLOAD_DIR`
- `JWT_SECRET`
- `TOKEN_TTL_HOURS`
- `CORS_ORIGINS`
- `PUBLIC_BASE_URL`
- `APP_TIMEZONE`
- `SCHEDULER_ENABLED`
- `APP_VERSION`
- `BOOTSTRAP_ADMIN_USER`
- `BOOTSTRAP_ADMIN_PASSWORD`
- `FCM_ENABLED`
- `FCM_PROJECT_ID`
- `FCM_SERVICE_ACCOUNT_FILE`

## Troubleshooting

### App zeigt `Server-Version: dev`
- Backend-Container ist alt oder nicht neu gezogen
- Stack neu deployen (mit aktuellem Image)
- Danach sollte `srv-...` erscheinen

### Keine Push-Benachrichtigung
- FCM nicht aktiv oder Service Account fehlt
- `/app/secrets/firebase-service-account.json` pruefen
- `FCM_ENABLED=true`, `FCM_PROJECT_ID` korrekt

### `invalid payload` im Admin
- Meist inkompatible alte Frontend-Version gecached
- Hard reload im Browser (`Ctrl+F5`)

### Android Release bricht ab
- Einer der 5 Android-Secrets fehlt/falsch
- Workflow `Release Android APK` Log pruefen

## Feedback und Triage
- In-App-Feedback und Fehler landen zuerst als Reports im Admin-Panel
- GitHub Issues sind die kuratierte technische Nachverfolgung, nicht der einzige Eingangskanal
- Fuer oeffentliche Releases sollten Changelogs immer Problem, Fix und Nutzerwirkung kurz benennen
- Admin-Reports und GitHub-Issues sollten inhaltlich aufeinander referenzieren, aber nicht doppelt gepflegt werden

## Sicherheit (kurz)
- Starkes `JWT_SECRET`
- Starkes Admin-Passwort
- Reverse Proxy nur ueber HTTPS
- Optional IP-Restriktion fuer Admin-Portal
- Debug-Profile in Produktivbetrieb nur bei Bedarf aktivieren
