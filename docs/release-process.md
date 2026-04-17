# Daily Release Process

## Release Types

- Server release: implicit on each `main` push via GHCR image publish
- Android release: explicit via semantic Git tag `vX.Y.Z`

## Server Release Path

1. Merge to `main`.
2. `CI` must pass.
3. `Publish Server Images` pushes `daily-backend` and `daily-admin` tags.
4. Deploy target image version and verify runtime health.

## Android Release Path

Precondition for workstations with the local Android toolchain:

- run the local debug build from `docs/android-local-toolchain.md` before tagging if the release includes Android changes

1. Set `versionName` and increment `versionCode` in `android/app/build.gradle.kts`.
2. Prepare release notes pair:
   - `.github/release-notes/vX.Y.Z.md`
   - `.github/release-notes/vX.Y.Z.json`
3. Push tag `vX.Y.Z`.
4. Workflow validates CI gate, version matching and secret availability.
5. Release publishes APK and `changelog.json` assets.

## Mandatory Acceptance Checks

Reference:

- `docs/testing/RELEASE_E2E_CHECKLIST.md`

Minimum checks before broad rollout:

- health endpoint green
- admin performance endpoints return expected schema keys
- app functional checks (feed, posting, prompt/409 behavior)
- queue/network error behavior remains controlled

## Release Documentation Rule

After each release/cutover, append an entry to:

- `reports/daily-ops-changelog.md`

Entry should include:

- commit/tag, build artifacts, deployment target, smoke result, rollback need (if any)
