# Daily GitHub Operations

## Repositories and Responsibilities

- Primary code repo: `flightuwe/selfhosted-daily-photo` (GitHub)
- Forge mirror and internal knowledge hub: Gitea `app-daily`

Governance:

- Code changes are authored and merged on GitHub.
- Internal operator documentation and operating context are preserved in Forge.
- If code and docs disagree, code behavior is validated against GitHub workflow state and docs are updated in Forge.

## GitHub Workflows in Production Use

### CI (`.github/workflows/ci.yml`)

Triggers:

- Push to `main`
- Pull requests

Checks:

- Go tests (`backend`)
- Admin build (`admin`)
- Android debug build (`android :app:assembleDebug`)

### Publish Server Images (`.github/workflows/publish-images.yml`)

Trigger:

- Push to `main`

Behavior:

- Builds backend/admin images and pushes to GHCR
- Applies tags:
  - `latest`
  - `sha-<shortsha>`
  - `srv-<run>.<attempt>`

### Release Android APK (`.github/workflows/release-android.yml`)

Trigger:

- Semantic tags `v*`

Gates:

- Requires successful `main` CI for tagged commit
- Requires `versionName` to match tag (`vX.Y.Z` <-> `X.Y.Z`)
- Requires `versionCode` increase vs previous tag
- Requires Android signing and `google-services.json` secrets

Release outputs:

- Signed APK asset
- `changelog.json`
- GitHub release notes (manual notes preferred, commit-summary fallback)

## Mirror Automation to Gitea

Workflow:

- `.github/workflows/sync-gitea-mirror.yml`

Default behavior:

- Runs on every push to `main` and can be started manually
- Pushes `main` to Gitea mirror repo branch `main`
- Updates mirror status files in Gitea:
  - `docs/mirror-policy.md`
  - `reports/daily-ops-changelog.md`

## Required GitHub-side Configuration

Set repository secret:

- `GITEA_TOKEN`: token with push rights to target Gitea repo

Optional repository variables:

- `GITEA_BASE_URL` (default: `http://10.20.10.55:3000`)
- `GITEA_OWNER` (default: `codex-agent` until org transfer is accepted)
- `GITEA_REPO` (default: `app-daily`)

## Operator Notes

- Keep release notes pair under `.github/release-notes/` for user-facing release quality.
- CI green plus publish success is the minimum precondition before production deploy.
- Mirror failures are production-ops relevant and must be documented in `reports/daily-ops-changelog.md` after recovery.
