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

Canonical mirror mode is now internal pull sync from cluster infrastructure.

Mechanism:

- Script: `ops-runbooks/scripts/daily-github-to-gitea-sync.ps1`
- Runbook: `ops-runbooks/runbooks/daily-github-gitea-mirror.md`
- Execution location: internal host with reachability to both GitHub and Gitea

Behavior:

- Fetches latest `main` from GitHub
- Pushes fast-forward mirror to Gitea target repo `main`
- Writes status metadata to `ops-runbooks/artifacts/daily/mirror-status.json`

GitHub workflow note:

- `.github/workflows/sync-gitea-mirror.yml` is retained as a legacy/manual placeholder.
- GitHub-hosted push-to-private-Gitea mode is intentionally disabled.

## Required Internal Configuration

The internal mirror host needs these env vars:

- `DAILY_GH_TOKEN`: GitHub PAT with repo read permissions
- `DAILY_GITEA_TOKEN`: Gitea token with repo write permissions

Optional env vars:

- `DAILY_GH_REPO` (default: `https://github.com/flightuwe/selfhosted-daily-photo.git`)
- `DAILY_GITEA_REPO` (default: `http://gitea.internal.local/org-or-user/app-daily.git`)
- `DAILY_WORKDIR` (default: `.cache/daily-mirror`)

## Operator Notes

- Keep release notes pair under `.github/release-notes/` for user-facing release quality.
- CI green plus publish success is the minimum precondition before production deploy.
- Mirror failures are production-ops relevant and must be documented in `reports/daily-ops-changelog.md` after recovery.

