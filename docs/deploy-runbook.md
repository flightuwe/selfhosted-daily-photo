# Daily Deploy Runbook

## Target Topology

Default production shape:

- Gateway `nginx` container exposing Daily service
- Backend + admin containers in the same stack network
- SQLite DB and uploads on mounted persistent volume
- Manual container update after successful GitHub image publish

Current active production target:

- Public runtime: `https://daily.broutschek.de`
- Internal Daily target: `http://10.20.10.30:13379`
- Proxmox CT: `9204`
- Stack path inside container: `/opt/daily/stack`

Retired/non-production targets:

- `https://daily.teacloud.synology.me`
- `http://192.168.178.80:13379`

## Service Roles

- `backend`: Go API, scheduler, persistence, uploads, health endpoints, admin APIs
- `admin`: static web UI for operational/admin views
- `gateway`: nginx front door; routes `/api/...` to `backend` and `/` to `admin`

Operational rule:

- backend-only change: update `backend`
- admin UI change: update `admin`
- nginx/routing change: update `gateway`

## Canonical Runtime Paths

From deployment templates and recovered production practice:

- DB: `backend-data/app.db`
- Uploads: `backend-data/uploads/`
- Secrets mount: `secrets/`
- Gateway config: `nginx/default.conf`
- Backend logs: `logs/backend/`
- Gateway logs: `logs/nginx/`

Known production notes:

- `CORS_ORIGINS` must include both internal and public origin values
- in the current Docker-in-LXC setup, the live gateway config may use fixed upstream IPs (`172.18.0.2` / `172.18.0.3`) to avoid `502`

## GitHub To Production Model

Code and image path:

1. `git push origin main`
2. GitHub runs `CI`
3. GitHub runs `Publish Server Images`
4. GHCR publishes fresh `backend` and `admin` images
5. Production rollout on Broutschek remains manual

Android path:

1. bump `android/app/build.gradle.kts`
2. push `main`
3. wait for successful `CI`
4. push semantic tag like `v0.4.28`
5. GitHub runs `Release Android APK`
6. GitHub publishes signed APK release with `changelog.json`

Server image tags produced by GitHub:

- `ghcr.io/flightuwe/daily-backend:latest`
- `ghcr.io/flightuwe/daily-backend:sha-<shortsha>`
- `ghcr.io/flightuwe/daily-backend:srv-<run>.<attempt>`
- same tag pattern for `daily-admin`

## Standard Deploy Procedure

1. Confirm `CI` and `Publish Server Images` are successful on target commit.
2. Confirm intended GHCR image tags (`latest`, `sha-*`, `srv-*`).
3. Update stack configuration and runtime environment values where needed.
4. Pull and recreate the relevant service containers.
5. Validate:
   - `GET /api/health` returns `ok: true`
   - `GET /api/health/live` returns the expected runtime build version
   - admin login works
   - upload path is writable
   - scheduler runtime view is healthy

## Manual Broutschek Update

Preferred operator path:

1. SSH to the Proxmox node currently hosting CT `9204`
2. Run `pct exec ...` from the Proxmox host shell as `root`
3. Use direct `docker compose ...` only if already inside the Daily container

Update backend only:

```bash
pct exec 9204 -- sh -lc 'cd /opt/daily/stack && docker compose pull backend && docker compose up -d backend && docker compose ps backend'
```

Update admin only:

```bash
pct exec 9204 -- sh -lc 'cd /opt/daily/stack && docker compose pull admin && docker compose up -d admin && docker compose ps admin'
```

Update backend + admin together:

```bash
pct exec 9204 -- sh -lc 'cd /opt/daily/stack && docker compose pull backend admin && docker compose up -d backend admin && docker compose ps'
```

If already inside the LXC shell:

```bash
cd /opt/daily/stack
docker compose pull backend admin
docker compose up -d backend admin
docker compose ps
```

## Post-Update Verification

Container-side check:

```bash
pct exec 9204 -- sh -lc 'cd /opt/daily/stack && docker compose logs --tail=100 backend'
```

External health check:

```bash
curl -fsSL https://daily.broutschek.de/api/health/live
```

Expected response shape:

- `ok: true`
- `status: "live"`
- `provider: "fcm"`
- `version` shows the current GHCR runtime build, for example `srv-249.1`

## Storage Operations

- Persistent Daily data lives under `/opt/daily/backend-data`, not inside
  `/opt/daily/stack`.
- Install `deploy/logrotate/daily-nginx` in the LXC as
  `/etc/logrotate.d/daily-nginx`; it retains 14 compressed daily gateway logs.
- For a complete storage report including Docker, use the constrained
  node-side `pct exec 9204` report. The backend API only reports its mounted
  data volume; it cannot safely inspect the Docker daemon.
- See `docs/storage-operations.md` for thresholds and validation.

Recovered reference rollout on 2026-04-17:

- commit on `main`: `67967f3`
- image publish: success
- backend runtime after manual update: `srv-249.1`
- Android release tag: `v0.4.28`

## Failure Notes

If the rollout command ends with something like `sh: 2: backend: not found` after a pasted multiline command:

- this is usually only a shell line-break artifact
- verify the preceding `docker compose ps` output before assuming rollout failure

If gateway starts returning `502` after recreating backend/admin:

1. inspect current container IPs
2. compare them to the live nginx upstream config
3. reload or correct gateway config if fixed upstream IPs are in use

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

## E-mail Support Rollout

1. Roll out backend/admin migrations while SMTP remains disabled.
2. Configure the e-mail master key, approved action hosts, Android package and release/debug fingerprints on CT `9204`.
3. Route every action domain to the gateway and verify `/.well-known/assetlinks.json` before publishing the APK.
4. Test the Posteo draft connection and a real test message in Admin → Konfiguration → E-Mail; enable delivery only after both succeed.
5. Publish Android with both App-Link hosts. Change `Action-Base-URL` only after the target host is live.
6. Keep the old domain reachable for at least 30 days plus the maximum token lifetime.
7. Watch queue depth, failed jobs and reset rate. Emergency rollback is the admin delivery switch; do not delete addresses, consent or encryption keys.

Full configuration and security invariants: `docs/email-support.md`.

## Post-Deploy Smoke

- `pwsh scripts/test-e2e-smoke.ps1 -BaseUrl <url> -AdminToken <token>`
- optional burst guard:
  - `pwsh scripts/test-feed-rate-limit.ps1 -BaseUrl <url> -UserToken <token> -Requests 80 -Concurrency 8`
