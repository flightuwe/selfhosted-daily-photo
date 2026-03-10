# Daily

![Daily Logo](assets/daily-logo.png)

Private, selfhosted Daily-Moment App (Android + Admin Web + Go Backend) fuer kleine Gruppen.

## Inhalt
- Ein Foto-Moment pro Tag
- Feed-Sperre bis zum eigenen Tagesmoment
- Extra-Bilder, Kalender, Chat, Sondermoment
- Admin-Panel fuer Nutzer, Events, Commands, Systemzustand
- Vollstaendig selfhosted auf Synology/Portainer moeglich

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
- `ci.yml`: Backend Tests + Admin Build
- `publish-images.yml`: baut/pusht Server-Images bei Push auf `main`
- `release-android.yml`: baut signierte APK bei Tag `v*`

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
   - Release-Body aus `release-notes.md`
   - Asset `changelog.json` fuer die App

Hinweis:
- Der Android-Release-Workflow ist absichtlich **tag-only** und akzeptiert nur semantische Tags `vX.Y.Z`.
- Falls `release-notes.md`, `changelog.json` oder APK fehlen, bricht der Workflow mit Fehler ab.

## Erste Inbetriebnahme testen
1. `https://daily.deine-domain.tld/api/health` -> `ok: true`
2. Admin Login mit Bootstrap-Admin
3. Testnutzer anlegen
4. Event manuell triggern
5. Android App installieren und einloggen
6. Tagesmoment posten, Feed/Kalender/Chat pruefen

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
- Workflow `release-android` Log pruefen

## Sicherheit (kurz)
- Starkes `JWT_SECRET`
- Starkes Admin-Passwort
- Reverse Proxy nur ueber HTTPS
- Optional IP-Restriktion fuer Admin-Portal
- Debug-Profile in Produktivbetrieb nur bei Bedarf aktivieren
