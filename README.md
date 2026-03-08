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

## Synology mit Portainer (empfohlen)

### Voraussetzungen

1. In GitHub muessen die Workflows `ci` und `publish-server-images` erfolgreich sein.
2. Die GHCR Images muessen vorhanden sein:
   - `ghcr.io/flightuwe/selfhosted-bereal-backend:latest`
   - `ghcr.io/flightuwe/selfhosted-bereal-admin:latest`
3. Wenn dein Repo/Package privat ist: in Portainer unter `Registries` eine GitHub Container Registry mit Personal Access Token hinterlegen.

### Stack in Portainer anlegen

1. Portainer -> `Stacks` -> `Add stack`
2. Name: z. B. `selfhosted-daily-photo`
3. Den Inhalt aus `deploy/portainer-stack.yml` in den Editor kopieren.
4. Platzhalterwerte ersetzen:
   - `PUBLIC_BASE_URL`
   - `CORS_ORIGINS`
   - `JWT_SECRET`
   - `BOOTSTRAP_ADMIN_PASSWORD`
5. `Deploy the stack` klicken.

### Netzwerk / Domain

- Der Stack stellt standardmaessig `8088` auf der Synology bereit (`gateway` Service).
- In Synology Reverse Proxy deine Domain (z. B. `photos.example.com`) auf `http://<synology-ip>:8088` weiterleiten.
- TLS/Let's Encrypt ueber Synology terminieren.

## Android App

- API Basis-URL in `android/app/build.gradle.kts` bei `API_BASE_URL` auf deine Domain setzen, z. B. `https://photos.example.com/api/`.
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

## Konkrete Synology Werte

Fuer Portainer Stack (`deploy/portainer-stack.yml`) setze mindestens:

- `PUBLIC_BASE_URL=https://photos.<deine-domain>`
- `CORS_ORIGINS=https://photos.<deine-domain>`
- `JWT_SECRET=<langes-zufaelliges-secret>`
- `BOOTSTRAP_ADMIN_PASSWORD=<starkes-passwort>`
- Host-Pfad fuer Daten: `/volume1/docker/selfhosted-daily-photo/backend-data`

Danach in Synology Reverse Proxy:
- Quelle: `https://photos.<deine-domain>`
- Ziel: `http://127.0.0.1:8088`

## Synology Setup fuer deine Domain

Empfohlenes Ziel im Reverse Proxy:
- Quelle (Host): `daily.teacloud.synology.me`
- Ziel: `http://127.0.0.1:13379`

Direkter Test im LAN (ohne Reverse Proxy):
- `http://192.168.178.80:13379`

Android API URL:
- `https://daily.teacloud.synology.me/api/`

## Fix fuer Gateway-Container auf Synology/Portainer

Wenn der `gateway`-Container mit `cat: can't open 'server'` abstuerzt, nutze die Datei-basierte Nginx-Konfiguration:

1. Ordner anlegen:
   - `/volume1/docker/selfhosted-daily-photo/nginx`
2. Datei anlegen:
   - `/volume1/docker/selfhosted-daily-photo/nginx/default.conf`
3. Inhalt aus `deploy/synology/nginx-default.conf` uebernehmen.
4. Stack mit `deploy/portainer-stack.yml` neu deployen.

## Optionaler Debug-Pfad (ein-/ausschaltbar)

Fuer schnellere Fehleranalyse kannst du einen separaten Debug-Stack deployen:

- Datei: `deploy/portainer-debug-stack.yml`
- Zweck: Dozzle Log-UI fuer alle Container mit Namen `shp-*`
- URL: `http://<synology-ip>:13380`

Ablauf:
1. Nur bei Bedarf deployen.
2. Fehler sammeln (gateway, backend, admin gleichzeitig).
3. Danach Debug-Stack wieder stoppen/loeschen.

Details: `deploy/DEBUGGING.md`
