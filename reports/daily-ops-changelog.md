# Daily Ops Changelog

This file is append-only operational history for Forge-side Daily continuity.

## 2026-07-12

- Prepared Android patch `v0.6.27` after analyzing `daily-diagnose-1783855910430.txt`, which showed heavy `feed_viewport_anchor_changed` churn instead of a fresh crash.
- Added global FeedItem normalization directly at Android API/dataset mapping boundaries so feed, calendar, bookmarks, search and hub-backed datasets all sanitize missing interaction metadata before state updates.
- Added dataset-specific `feed_item_meta_normalized` diagnostics so future exported logs show exactly which source payload produced missing `interactionSnapshot` or `interactionCounts` metadata.
- Decoupled the visible pull-to-refresh spinner from background feed refreshes and reduced viewport-anchor state churn by ignoring minor visible-range changes, so the feed refresh indicator no longer spins during passive reloads and scroll jitter should reduce noticeably.
- Re-verified locally with `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain --max-workers=1` after clearing corrupted local Android build caches.

- Released combined server/android hardening `v0.6.25` from `main` commit `806b0d87a8428dd033f5d861e234f4c89963d6ac` after unifying Hub-to-Feed interaction resolution for comments, reactions and FotoMojis.
- Added preview-vs-full interaction semantics across backend and Android feed models so timeline targets can force a precise post-interaction reload instead of relying on stale feed previews.
- Extended diagnostics with dedicated target-resolution and interaction-refresh events to make future Timeline-versus-Feed divergence cases directly visible in exported client logs.
- Verified locally with `go test ./internal/api/...`, `gradle -p android testDebugUnitTest --no-daemon --stacktrace --console=plain --max-workers=1` and `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain --max-workers=1`.
- Confirmed GitHub Actions success for `CI` run `29186912127`, `Publish Server Images` run `29186912180` and `Release Android APK` run `29187032968`.
- Rolled Broutschek CT `9204` manually, pulled `backend` and `admin`, recreated both containers and verified internal `/api/health`, internal `/api/health/live` and external `https://daily.broutschek.de/api/health/live` on runtime `srv-330.1`.
- Confirmed GitHub release `v0.6.25` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.25`.

## 2026-07-11

- Released Hub warm-start and loading-state hardening `v0.6.22` from `main` commit `8ed17aa84446f6f942ec26ccff6b5d854e537580`.
- Added app-side warm cache persistence for small Hub bootstrap, timeline and public-calendar snapshots so the Hub can render a believable last-known state immediately after startup while fresh data loads in the background.
- Reworked Hub and calendar surface states with explicit cached, loading, refreshing and failure semantics so empty cards and premature `0 Nutzer haben gepostet` placeholders no longer appear when data is only partially loaded.
- Reduced refresh churn by introducing freshness windows for Hub bootstrap, timeline and public calendar loads and by decoupling the global refresh loop from immediate tab-switch restarts.
- Verified locally with `gradle :app:compileDebugKotlin`, `gradle :app:assembleDebug --no-daemon --stacktrace --console=plain` and `go test ./internal/api/... ./cmd/server`.
- Confirmed GitHub Actions success for `CI` run `29149549332`, `Publish Server Images` run `29149549330` and `Release Android APK` run `29149694380`.
- Rolled Broutschek CT `9204` manually, pulled `backend` and `admin`, recreated both containers and verified internal plus external `/api/health/live` on runtime `srv-327.1`.
- Confirmed GitHub release `v0.6.22` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.22`.

## 2026-07-11

- Released combined Hub hardening `v0.6.21` from `main` commit `5a2ad363e0e6c640f5eadc94befe2ac9457f9028` after fixing duplicate Hub timeline entries on bookmarked interactions, restoring owner interaction visibility on bookmarked foreign posts and extending timeline system events for backend/app updates.
- Added feed navigation trace IDs and source labels for Hub-to-Feed jumps plus background viewport restores, and downgraded benign Compose cancellation cases so `LeftCompositionCancellationException` no longer pollutes feed error diagnostics.
- Verified locally with `go test ./internal/api/... ./cmd/server` and `gradle :app:assembleDebug --no-daemon --stacktrace --console=plain` at `versionName=0.6.21`, `versionCode=141552`.
- Confirmed GitHub Actions success for `CI` run `29148055294`, `Publish Server Images` run `29148055313` and `Release Android APK` run `29148208997`.
- Rolled Broutschek CT `9204` manually via Proxmox host `192.168.0.40`, pulled `backend` and `admin`, recreated both containers and verified internal plus external health on runtime `srv-326.1`.
- Confirmed GitHub release `v0.6.21` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.21`.

## 2026-07-10

- Released Hub rollout `v0.6.17` from `main` commit `d2def6c2acc3a726a44b6c0d8102ac9b204aa895` with the former calendar tab rebuilt into a broader Hub including dashboard entry points, a 7-day personalized timeline and highlighted feed jump targets for comments, reactions and FotoMojis.
- Added backend Hub APIs for bootstrap, timeline, timeline clear and time-capsule sections, plus persisted per-user Hub timeline state and server-side synthesis of personalized activity items respecting the existing notification preference flags.
- Verified locally with `go test ./internal/api ./cmd/server` and `gradle :app:compileDebugKotlin`.
- Confirmed GitHub Actions success for `CI` run `29117494677`, `Publish Server Images` run `29117494672` and `Release Android APK` run `29117845817`.
- Rolled Broutschek CT `9204` manually via `docker compose pull backend admin` and `docker compose up -d backend admin`, then verified internal and external health endpoints on runtime `srv-322.1`.
- Confirmed GitHub release `v0.6.17` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.17`.

## 2026-07-06

- Released Android patch `v0.6.15` from `main` commit `384d5a29d347b4a7c0fff244d0568044789d0a3f` after fixing the broken long-press delete flow for own feed comments and the stale delete-feature recovery state after transient backend failures.
- Unified the 3-second delete-hold logic for chat messages and feed comments on Android, added explicit delete-gesture diagnostics, and introduced active post-recovery feature syncs so `chatDeleteSupported` / `commentDeleteSupported` no longer stay stuck `false` until app restart.
- Verified locally with `gradle :app:assembleDebug --no-daemon --stacktrace --console=plain` at `versionName=0.6.15`, `versionCode=141546`.
- Confirmed GitHub Actions success for `CI` run `28812965922`, `Publish Server Images` run `28812965905` and `Release Android APK` run `28813288445`.
- Confirmed GitHub release `v0.6.15` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.15`.
- Verified the production rollout manually after image publication and confirmed healthy live runtime behaviour on the active deployment target.

## 2026-07-02

- Released combined server/android stabilization `v0.6.6` from `main` commit `415053d11e272ca5646f501d703db2f8e53e2676` after merge of PR `#2`.
- Hardened Android queued uploads against worker replacement, added a process-wide auth session coordinator, classified post-body ACK timeouts as safe retry state and reduced push/refresh cancellation noise.
- Hardened backend refresh-token rotation and upload retry idempotency with stable `errorCode` responses plus regression coverage for `uploadClientId` retry and auth refresh contract handling.
- Verified locally with `go test ./internal/api/...`, `npm run build`, `gradle -p android :app:assembleDebug --no-daemon --stacktrace --console=plain`; full local `go test ./...` on this workstation still remains environment-limited when SQLite runs without CGO.
- Verified GitHub Actions success for `CI` run `28614842092`, `Publish Server Images` run `28614842184` and `Release Android APK` run `28615166991`.
- Confirmed GHCR manifests for `ghcr.io/flightuwe/daily-backend:sha-415053d`, `ghcr.io/flightuwe/daily-backend:srv-302.1` and `ghcr.io/flightuwe/daily-admin:sha-415053d`.
- Confirmed GitHub release `v0.6.6` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.6`.
- Device smoke and live runtime checks against `daily.broutschek.de` were not executed from this workstation during this release cut; follow-up should cover health/admin endpoints plus real upload/queue behavior on device.

## 2026-07-03

- Released Android patch `v0.6.10` from `main` commit `023571c7e4e8cb351cd3bbfc03734de267aa3cc7`; backend code was unchanged, while GitHub still published the standard server image set for the same commit.
- Finalized the deep-feed stabilization by keeping visible feed anchors valid even when they fall outside the freshly fetched feed-day index and by avoiding unnecessary auto-refresh jumps back to today.
- Hardened feed diagnostics semantics so failed viewport restores now degrade the refresh result explicitly instead of reporting a misleading success immediately afterwards.
- Smoothed repeated manual pull-to-refresh while a feed refresh is already running by merging the request into the active refresh queue instead of spamming duplicate deferred events.
- Verified locally with `gradle :app:compileDebugKotlin --no-daemon` and `gradle :app:assembleDebug --no-daemon --stacktrace --console=plain`; one transient workstation-only Android resource/Kotlin incremental failure cleared on immediate rerun.
- Confirmed GitHub Actions success for `CI` run `28654437532`, `Publish Server Images` run `28654437592` and `Release Android APK` run `28654684488`.
- Confirmed GitHub release `v0.6.10` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.10`.

- Released Android patch `v0.6.9` from `main` commit `18d5f7593626556d9c31a3e0526240652d385f94`; backend code was unchanged, while GitHub still published the standard server image set for the same commit.
- Fixed the remaining deep-feed auto-refresh jump by clearing stale jump-navigation state after consumption and by preserving the effective viewport anchor even when old focus state still exists.
- Tightened diagnostics by exporting primarily the current app session and by adding notification-clear verification/retry logging for stubborn active notifications.
- Verified locally with `gradle :app:compileDebugKotlin --no-daemon` and `gradle :app:assembleDebug --no-daemon --stacktrace --console=plain`.
- Confirmed GitHub Actions success for `CI` run `28652705307`, `Publish Server Images` run `28652705196` and `Release Android APK` run `28652959043`.
- Confirmed GitHub release `v0.6.9` with assets `app-release.apk` and `changelog.json`: `https://github.com/flightuwe/selfhosted-daily-photo/releases/tag/v0.6.9`.

- Prepared combined server/android patch release `v0.6.7` from `main` for connection-health visibility, queue observability follow-up and stable `daily_required` / `upload_window_closed` errorCode contracts.
- Added optional camera-tab connection health indicator with live popup details, local status evaluation from network/refresh/queue signals and Android fallback-safe `errorCode` parsing for upload and access failures.
- Added backend regression coverage for `upload_window_closed` and `daily_required`, plus Android unit coverage for connection-health evaluation and refresh-lock serialization.
- Locally verified before release with `go test ./internal/api/...`, `gradle -p android :app:testDebugUnitTest --no-daemon --stacktrace --console=plain` and `gradle -p android clean :app:assembleDebug --no-daemon --stacktrace --console=plain`.
- Real device validation and live checks against `daily.broutschek.de` remain follow-up work after the GitHub/GHCR release path completes.

- Prepared combined server/android release `v0.6.5` with `versionName=0.6.5` and `versionCode=141536` for the chat composer, chat-length controls and push-diagnostics follow-up.
- Added global admin-managed chat message limits with default `5000` characters, optional unlimited mode, server-side normalized length validation and portable text storage for long chat bodies.
- Reworked the Android chat composer into a multi-line mobile layout with inline limit feedback, local over-limit blocking and richer debug metadata for failed chat sends.
- Extended notification diagnostics so real FCM deliveries are marked separately from local debug scenarios and clear-path metadata remains exportable for follow-up analysis.
- Verified locally with `go test ./internal/api`, `go build ./cmd/server`, `npm run build`, `gradle :app:compileDebugKotlin`, `gradle :app:testDebugUnitTest`, `gradle :app:assembleDebug` and `gradle :app:assembleRelease`; full `go test ./...` remains locally blocked by missing CGO/GCC for SQLite-backed scheduler tests.

- Prepared Android patch release `v0.6.4` with `versionName=0.6.4` and `versionCode=141535` for the new optional notification-debug tooling.
- Kept the release Android-only; no backend rollout is required for these diagnostic and local notification-lab additions.
- Added a gated notification-debug mode with push-event history, launch-intent tracking, active-notification snapshots, export bundles, local scenario generation and clear-lab controls.
- Verified locally with `gradle :app:compileDebugKotlin`, `gradle :app:testDebugUnitTest`, `gradle :app:assembleDebug` and `gradle :app:assembleRelease` before GitHub release preparation.

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

