# Release E2E Checklist (Backend + Admin + Android)

Diese Checkliste ist verbindlich vor und nach einem App-Tag-Release.

## 1) Pre-Release Gate (`main`)

1. CI ist gruen:
   - backend tests (`go test ./...`)
   - admin build (`npm run build`)
   - android debug build (`:app:assembleDebug`)
2. `Publish Server Images` ist erfolgreich.
3. Smoke-Checks gegen Zielsystem:
   - `GET /api/health` -> `ok: true`
   - `GET /api/admin/performance/overview` enthaelt:
     - `schemaVersion`
     - `errorClasses`
     - `summary.throttleRate`
     - `summary.throttleCount`
   - `GET /api/admin/performance/export?format=json` enthaelt:
     - `overview`
     - `routes`

## 2) App-Release Gate (Tag)

1. `versionName == Tag` (z. B. `v0.4.13` <-> `0.4.13`)
2. `versionCode` groesser als im vorherigen Tag
3. Release Workflow erfolgreich:
   - APK-Asset vorhanden
   - `changelog.json` vorhanden

## 3) Funktionale Abnahme auf Geraet

1. Versionsanzeige korrekt, kein falscher "Update verfuegbar"-Loop.
2. Feed:
   - langer Scroll + Refresh springt nicht nach oben
   - Pull-to-refresh zeigt neue Posts/Kommentare derselben sichtbaren Tage
3. Daily-Logik:
   - Extra ausserhalb Fenster
   - Prompt im Fenster
   - zweiter Prompt -> `409`
4. Netzfehler:
   - DNS/Timeout/Offline erzeugt Backoff statt Request-Sturm
5. Queue:
   - Fehlermeldungen enthalten Fehlerklasse

## 4) Daily-Spike Test (15-30 min)

1. 10-20 parallele Clients/Burst gegen Feed + Upload + Kommentare
2. Erwartung:
   - keine 5xx-Spitze
   - kontrollierte `429` (mit `Retry-After`/reason)
   - p95/p99 im Rahmen der Baseline
3. JSON-Report sichern:
   - `overview`
   - `routes`
   - `spikes`
   - `slo`

## 5) 24h Monitoring nach Release

Alle 2-4h prüfen:

1. Fehlerklassenmix (`dns/connect/timeout/http4xx/http5xx`)
2. Anteil `429` vs. 5xx
3. Feed-Refresh-Fehlerquote
4. Upload-Queue-Fehler
5. API p95/p99 Verlauf

Wenn 5xx oder Timeout auffaellig steigen:
- auf letzten stabilen Backend-Image-Tag zurueckrollen
- App-Rollout pausieren

## Hilfsskripte

- Smoke:
  - `pwsh scripts/test-e2e-smoke.ps1 -BaseUrl https://daily.teacloud.synology.me -AdminToken <token>`
- Feed Burst / Rate-Limit:
  - `pwsh scripts/test-feed-rate-limit.ps1 -BaseUrl https://daily.teacloud.synology.me -UserToken <token> -Requests 80 -Concurrency 8`
