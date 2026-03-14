# Daily Trigger Incident Runbook (Synology/SQLite)

This runbook is for incidents where multiple Daily-Moment notifications or trigger attempts occur.

## 1) Immediate containment

1. Open Admin Panel -> Analyse -> Incident Export.
2. Click `Scheduler pausieren`.
3. Click `Incident JSON herunterladen` with `last 60 minutes`.
4. If lease ownership looks stale, click `Lease freigeben` once.

Fail-closed policy: pausing scheduler is preferred over risking duplicate trigger notifications.

## 2) Runtime checks

In Incident Export -> Trigger Runtime, confirm:

- `Auto-Pause`: active/inactive.
- `Lease Owner` and `Ist Owner?`.
- `Attempts`, `Blocked`, `Failed`, `DB-Lock`, `Duplikate heute`.
- `Last Tick` and `Ergebnis`.

If `DB-Lock` rises quickly, keep scheduler paused and continue forensic export.

## 3) Forensic export checklist

Incident bundle should include:

- `triggerAudit`
- `triggerSummary`
- `schedulerLeaseState`
- `triggerCoordinatorState`
- `dispatchDedupeState`
- `rootCauseHints`
- `performance`
- `rawBackendLogExcerpt`
- `rawGatewayLogExcerpt` (if mounted)

If collection warnings show missing log paths, set:

- `FORENSIC_BACKEND_LOG_PATH`
- `FORENSIC_GATEWAY_LOG_PATH`

and redeploy.

## 4) Recovery

1. Verify no new duplicate attempts in Trigger Runtime.
2. Verify lease owner is stable.
3. Click `Scheduler fortsetzen`.
4. Monitor Trigger Runtime for at least 10 minutes.

## 5) Escalation rules

- Keep scheduler paused if:
  - repeated `db_locked` failures,
  - lease owner flapping,
  - duplicate attempts continue.
- Only run `admin_reset` if explicitly needed and documented in incident notes.

## 6) Fast post-deploy smoke check

Use:

```powershell
pwsh ./scripts/smoke-trigger-runtime.ps1 -BaseUrl "https://YOUR_DOMAIN" -AdminToken "YOUR_ADMIN_JWT" -WindowMinutes 60
```

Expected:

- health `ok=true`
- trigger-runtime returns lease/runtime/recent data
- incident status returns schema + duplicate counters
- warnings are empty or explicitly explain missing log mounts
