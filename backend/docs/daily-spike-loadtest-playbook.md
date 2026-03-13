# Daily Spike Loadtest Playbook (v1)

## Ziel
Reproduzierbarer Lasttest fuer das Daily-Moment-Fenster mit vergleichbaren Vorher/Nachher-Ergebnissen.

## Szenario
1. Daily-Moment triggern.
2. Parallel fuer 10-15 Minuten:
   - `GET /api/feed?day=today`
   - `POST /api/uploads` (prompt/extra gemischt)
   - `POST /api/photos/{id}/comments`
3. Danach 5 Minuten Cooldown laufen lassen.

## Auswertung
Nutze folgende Endpoints fuer denselben Zeitraum:
- `GET /api/admin/performance/overview`
- `GET /api/admin/performance/routes`
- `GET /api/admin/performance/spikes`
- `GET /api/admin/performance/slo`
- `GET /api/admin/performance/export?format=json`

## Report-JSON (Empfehlung)
```json
{
  "runId": "daily-spike-2026-03-13T16:30:00+01:00",
  "environment": "prod|staging",
  "window": {
    "from": "2026-03-13T16:30:00+01:00",
    "to": "2026-03-13T16:50:00+01:00"
  },
  "summary": {
    "requests": 0,
    "errors": 0,
    "p95PeakMs": 0,
    "p99PeakMs": 0
  },
  "sloStatus": "ok|breach",
  "topRoutes": [],
  "dbHotspots": [],
  "spikes": []
}
```

## Akzeptanzkriterien
- `p95 /feed` sinkt oder bleibt stabil.
- 5xx-Rate bleibt unter SLO-Grenze.
- Upload-Error-Rate bleibt unter SLO-Grenze.
- Keine unerklaerten neuen DB-Hotspots.
