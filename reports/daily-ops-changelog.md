# Daily Ops Changelog

This file is append-only operational history for Forge-side Daily continuity.

## 2026-06-30

- Prepared Android patch release `v0.6.3` with `versionName=0.6.3` and `versionCode=141534` for the notification cleanup and push-preference hardening work.
- Fixed Android push handling so chat poll notifications now respect the dedicated local poll toggle and app entry clears previously tracked Daily push notifications from the shade.
- Introduced stable notification grouping/ID rules plus JVM test coverage for push filtering, grouping and ID stability to reduce silent regressions before the next APK rollout.
- Verified locally with `gradle :app:compileDebugKotlin`, `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`; release assembly status recorded separately based on local signing availability.

## 2026-06-18

- Prepared Android patch release `v0.6.2` with `versionName=0.6.2`, `versionCode=141533`, release notes and signed APK assets on GitHub.
- Improved Android diagnostics around refresh orchestration, preference-sync conflict detection, FCM/device-token registration and optimistic feed-mutation reconciliation.
- Verified locally with `gradle -p android :app:compileDebugKotlin` and `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain` before push/tag.
- Confirmed successful GitHub Actions completion for `CI`, `Publish Server Images` and `Release Android APK`; release URL: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.2`.

- Prepared the combined feature release `v0.6.0` with `versionName=0.6.0`, `versionCode=141531`, Android release notes and integrated backend/android test coverage.
- Merged the NSFW post flow with profile opt-in, feed obscuring, YOLO defaults, bookmark-aware push events and auto-subscribe support for foreign NSFW markings.
- Integrated the multi-image append flow into feed rendering, upload queue handling and bookmarked post change notifications, including duplicate protection against existing primary/secondary media.
- Fixed the bookmark/auto-subscribe edge case so manual unbookmarking no longer recreates an interaction subscription immediately via the bookmark delete handler.
- Re-verified locally with `go test ./internal/api/...` and `gradle :app:compileDebugKotlin`; Android still reports only pre-existing warnings outside this release scope.

## 2026-05-30

- Prepared combined server/android patch release `v0.5.14` with `versionName=0.5.14`, `versionCode=141523` for the today-feed lock regression.
- Restored consistent server-side visibility rules so the new `/api/feed/window` path no longer exposes today’s foreign posts before the viewer has posted.
- Added backend regression coverage for `feed/window` and `feedDaysForUser()` plus Android-side `feed_locked` handling and cache cleanup.
- Re-verified locally with `go test ./internal/api/...` and `gradle :app:compileDebugKotlin` before release push.

- Prepared Android release `v0.5.13` with `versionName=0.5.13`, `versionCode=141522` for the calendar featured-post jump fix.
- Adjusted calendar day-level feed actions so `Im Feed oeffnen` targets the day’s highlighted top post whenever `featuredPhoto.photoId` is present.
- Re-verified Android locally with `gradle :app:compileDebugKotlin` before the patch release.

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

