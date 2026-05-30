# Daily Ops Changelog

This file is append-only operational history for Forge-side Daily continuity.

## 2026-05-30

- Prepared Android release `v0.5.12` with `versionName=0.5.12`, `versionCode=141521` for the feed quick-action jump fix.
- Adjusted feed quick-action buttons so `nach oben` and `nach unten` target the real known feed edges instead of only the currently loaded window.
- Re-verified Android locally with `gradle :app:compileDebugKotlin` before the patch release.

- Prepared Android release `v0.5.11` with `versionName=0.5.11`, `versionCode=141520` and release notes for anchor-window feed loading.
- Split feed navigation rollout into two phases: backend on `main` first, Android release second, because the app now depends on the new `/api/feed/window` endpoint.
- Added anchor-window feed loading for the Android app so calendar jumps fetch the target day plus neighboring days in one request and show an immediate loading state at the landing day.
- Re-verified Android locally with `gradle :app:compileDebugKotlin` before release push; backend API tests for the supporting server endpoint were already green on commit `077af6f`.

- Prepared Android release `v0.5.10` with `versionName=0.5.10`, `versionCode=141519` and release notes for the new feed/calendar scroll quick navigation.
- Added Android-only UX improvements for long feed and calendar sessions: floating jump actions, faster return to today/current anchor and clearer jump landing highlights.
- Re-verified Android locally with `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain` before GitHub rollout.

- Hardened Android queued uploads for unstable/offline networks with shared auth refresh, richer queue state tracking, German user-facing status text and additional upload telemetry/debug events.
- Added backend offline-grace handling for delayed prompt uploads based on `captured_at`, including `acceptedViaOfflineGrace` responses and regression coverage in `backend/internal/api/handlers_test.go`.
- Added new admin analytics surfaces for upload debugging: upload timeline, condensed debug summary view and copyable timeline log output for faster incident forensics.
- Re-verified repository health locally with `go test ./internal/api/...`, `npm run build` and `gradle :app:compileDebugKotlin`; Android still emits only pre-existing warnings.

## 2026-04-17

- Prepared GitHub release `v0.4.28` with Android `versionName=0.4.28` and `versionCode=79`.
- Hardened backend runtime version resolution so legacy placeholder values like `migration-prep` no longer hide the embedded build version from GHCR images.
- Recorded release notes pair for `v0.4.28` and refreshed deploy guidance for `APP_VERSION` placeholder handling.
- Revalidated the active production path on Broutschek (`daily.broutschek.de`, CT `9204`) including manual backend rollout from Proxmox host via `pct exec ... docker compose ...`.
- Verified post-rollout live backend version on production as `srv-249.1`.

## 2026-05-26

- Released calendar full-text search and clickable hashtag support on `main` commit `82ec3cc`.
- Published GHCR server images for commit `82ec3cc` with runtime version `srv-266.1`.
- Published Android release `v0.4.41` with `versionName=0.4.41`, `versionCode=92`, signed APK asset and `changelog.json`.
- Verified GitHub Actions health had recovered and monitored the successful runs for `CI`, `Publish Server Images` and `Release Android APK`.
- Production runtime is still on `srv-262.1`; manual Broutschek rollout remains pending because the documented `CT 9204` host path could not be reached from this workstation on the currently known node access path.

## 2026-04-14

- Established Daily Forge Knowledge Hub docs (`docs/overview.md` and linked runbooks).
- Added GitHub to Gitea mirror workflow definition (`.github/workflows/sync-gitea-mirror.yml`).
- Defaulted mirror target to `org-or-user/app-daily` pending organization transfer acceptance.
- Added operator cross-references in `infra-forge-gitea` and `ops-runbooks`.
- Switched canonical mirror mode to internal pull sync from cluster side.
- Added internal script/runbook path (`ops-runbooks/scripts/daily-github-to-gitea-sync.ps1` and `ops-runbooks/runbooks/daily-github-gitea-mirror.md`).
- Disabled GitHub-hosted push-to-private-Gitea as default path.
- Added cluster handoff checklist for direct Gitea follow-up actions: `docs/gitea-direct-todo.md`.

