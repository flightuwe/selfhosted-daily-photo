# Daily Ops Changelog

This file is append-only operational history for Forge-side Daily continuity.

## 2026-04-17

- Prepared GitHub release `v0.4.28` with Android `versionName=0.4.28` and `versionCode=79`.
- Hardened backend runtime version resolution so legacy placeholder values like `migration-prep` no longer hide the embedded build version from GHCR images.
- Recorded release notes pair for `v0.4.28` and refreshed deploy guidance for `APP_VERSION` placeholder handling.

## 2026-04-14

- Established Daily Forge Knowledge Hub docs (`docs/overview.md` and linked runbooks).
- Added GitHub to Gitea mirror workflow definition (`.github/workflows/sync-gitea-mirror.yml`).
- Defaulted mirror target to `org-or-user/app-daily` pending organization transfer acceptance.
- Added operator cross-references in `infra-forge-gitea` and `ops-runbooks`.
- Switched canonical mirror mode to internal pull sync from cluster side.
- Added internal script/runbook path (`ops-runbooks/scripts/daily-github-to-gitea-sync.ps1` and `ops-runbooks/runbooks/daily-github-gitea-mirror.md`).
- Disabled GitHub-hosted push-to-private-Gitea as default path.
- Added cluster handoff checklist for direct Gitea follow-up actions: `docs/gitea-direct-todo.md`.

