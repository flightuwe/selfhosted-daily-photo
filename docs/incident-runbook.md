# Daily Incident Runbook (Trigger/Scheduler)

## Scope

This runbook covers duplicate daily trigger attempts, scheduler instability, and runtime lock contention.

## Immediate Containment

1. Open admin panel incident tools.
2. Pause scheduler first (fail-closed).
3. Export incident JSON (last 60 minutes).
4. Release lease only once if lease ownership appears stale.

## Runtime Signals to Verify

- Auto-pause status
- Lease owner and ownership consistency
- Attempt/block/failure counters
- DB-lock trend
- Duplicate count for current day
- Last tick result

## Forensic Bundle Minimum

Incident export should include:

- trigger audit and summary
- lease and coordinator state
- dispatch dedupe state
- root-cause hints
- performance snapshot
- backend log excerpt
- gateway log excerpt (if mounted)

## Recovery Procedure

1. Ensure duplicates no longer increase.
2. Ensure lease owner is stable.
3. Resume scheduler.
4. Observe runtime for at least 10 minutes.

## Escalation Conditions

Keep scheduler paused and escalate if any of the following remain true:

- repeated `db_locked` failures
- lease owner flapping
- duplicate attempts continue

## Fast Runtime Smoke

- `pwsh scripts/smoke-trigger-runtime.ps1 -BaseUrl <url> -AdminToken <token> -WindowMinutes 60`

Expected outcome:

- healthy API status
- runtime payload includes lease/runtime/recent data
- warnings are empty or explicitly explained by missing log mounts
