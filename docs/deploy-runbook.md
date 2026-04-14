# Daily Deploy Runbook

## Target Topology

Default production shape:

- Synology + Portainer stack
- Gateway `nginx` container exposing Daily service
- Backend + admin containers in same stack network
- SQLite DB and uploads on mounted persistent volume

## Canonical Runtime Paths

From deployment templates and migration practice:

- DB: `backend-data/app.db`
- Uploads: `backend-data/uploads/`
- Secrets mount: `secrets/`
- Gateway config: `nginx/default.conf`
- Backend logs: `logs/backend/`
- Gateway logs: `logs/nginx/`

## Standard Deploy Procedure

1. Confirm `CI` and `Publish Server Images` are successful on target commit.
2. Confirm intended image tags (`latest`, `sha-*`, `srv-*`).
3. Update stack configuration and environment values where needed.
4. Deploy via Portainer (`pull` + `redeploy`) or compose update.
5. Validate:
   - `GET /api/health` returns `ok: true`
   - admin login works
   - upload path writable
   - scheduler runtime view is healthy

## Cutover / Migration Procedure

For full host migration, use:

- `docs/server-migration-playbook.md`
- `scripts/backup-full.(ps1|sh)`
- `scripts/restore-full.(ps1|sh)`
- `scripts/validate-stack.(ps1|sh)`

Critical migration rule:

- SQLite copy must be taken from a quiesced/paused write state to avoid malformed DB artifacts.

## Rollback Defaults

- Keep previous stack warm for fast fallback when feasible.
- Revert DNS/proxy to previous known-good endpoint.
- Document rollback reason and symptom class in `reports/daily-ops-changelog.md`.

## Post-Deploy Smoke

- `pwsh scripts/test-e2e-smoke.ps1 -BaseUrl <url> -AdminToken <token>`
- Optional burst guard:
  - `pwsh scripts/test-feed-rate-limit.ps1 -BaseUrl <url> -UserToken <token> -Requests 80 -Concurrency 8`
