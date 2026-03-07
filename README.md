# Selfhosted Daily Photo (BeReal-like)

Monorepo mit:
- `backend/` Go API (JWT, Prompt-Fenster, Uploads, Feed, Admin-Endpoints)
- `admin/` React Admin-Panel
- `android/` Android App (Kotlin + Compose)
- `deploy/` Docker Compose + Nginx fuer Synology

## Architektur

- Bilder liegen auf deinem Server-Dateisystem (`/app/data/uploads` im Backend-Container).
- DB ist SQLite (`/app/data/app.db`) fuer kleinen Serverbetrieb.
- Prompt-Zeitfenster und Upload-Dauer sind im Admin-Panel konfigurierbar.
- Scheduler erzeugt einmal taeglich einen zufaelligen Prompt-Zeitpunkt innerhalb des konfigurierten Fensters.

## Schnellstart lokal

1. `Copy-Item backend/.env.example backend/.env`
2. `docker compose -f deploy/docker-compose.yml up --build`
3. Admin auf `http://localhost`
4. API auf `http://localhost/api`

## Synology Deployment

1. Repo auf die Synology klonen (z. B. `/volume1/docker/selfhosted-bereal`).
2. `backend/.env` erstellen:
   - `PUBLIC_BASE_URL=https://deine-domain.tld`
   - `JWT_SECRET` setzen
   - `BOOTSTRAP_ADMIN_USER` und `BOOTSTRAP_ADMIN_PASSWORD` setzen
3. Ports/Reverse Proxy so konfigurieren, dass deine Domain auf den `nginx`-Service zeigt.
4. Start:
   - `docker compose -f deploy/docker-compose.yml up -d --build`
5. Persistenz:
   - Daten liegen in `deploy/data/backend` (DB + Uploads)

## Android App

- API Basis-URL in `android/app/build.gradle.kts` bei `API_BASE_URL` auf deine Domain setzen, z. B. `https://deine-domain.tld/api/`.
- Build lokal:
  - `gradle -p android :app:assembleRelease`

## GitHub Setup

### 1. Repo anlegen und pushen

```powershell
git init
git add .
git commit -m "Initial selfhosted daily photo stack"
git branch -M main
git remote add origin <dein-github-repo-url>
git push -u origin main
```

### 2. CI/CD

Vorhandene Workflows:
- `.github/workflows/ci.yml` (Go + Admin Build)
- `.github/workflows/publish-images.yml` (GHCR Images bei Push auf main)
- `.github/workflows/release-android.yml` (APK bei Tag `v*`)

### 3. APK Release

```powershell
git tag v0.1.0
git push origin v0.1.0
```

Danach liegt `app-release.apk` in GitHub Releases.

## API Kurzuebersicht

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/prompt/current`
- `POST /api/uploads` (`multipart/form-data`: `photo`, `kind=prompt|extra`, optional `caption`)
- `GET /api/feed`
- `GET /api/admin/settings`
- `PUT /api/admin/settings`
- `POST /api/admin/prompt/trigger`
- `POST /api/admin/users`

## Naechste sinnvolle Schritte

1. FCM-Provider implementieren (`notify` Paket) fuer echte Push-Zustellung.
2. Kamera-Flow in Android (Front/Back Capture im Prompt-Moment).
3. Rollen/Rechte erweitern und Audit-Log im Admin-Panel.
4. Optionaler Wechsel von SQLite auf Postgres fuer groessere Nutzerzahl.
