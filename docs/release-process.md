# Daily Release Process

## Release Types

- Server release: operator-controlled deployment from an accepted Forgejo commit
- Android release: explicit via semantic Git tag `vX.Y.Z`

## Versioning Policy

Daily uses semantic version numbers conservatively while the product is below `1.0`:

- Prefer patch releases within the current minor line (`0.8.0` -> `0.8.1` -> `0.8.2`) for fixes, optimizations, UI refinements and ordinary feature additions.
- Do not increment the minor version merely because several changes are bundled into one release.
- Increment the minor version (`0.8.x` -> `0.9.0`) only for a clearly substantial product or architecture milestone, such as a major persistence/sync redesign, a broad compatibility boundary or a similarly large coordinated change.
- Record the reason for every minor-version increment in the release notes before creating the tag.
- Major version `1.0.0` remains an explicit product-readiness decision and is never inferred from release count.

Historical decision: `v0.8.0` was accepted as an exceptional minor increment for the combined media, cache, synchronization and data-usage architecture release. Subsequent releases should normally remain on the `0.8.x` line until another comparably significant milestone is deliberately approved.

## Server Release Path

1. Merge to `main`.
2. `CI` must pass.
3. Build or select the deployment artifact from the accepted Forgejo commit.
4. Deploy the exact target version and verify runtime health.

## Android Release Path

Precondition for workstations with the local Android toolchain:

- run the local debug build from `docs/android-local-toolchain.md` before tagging if the release includes Android changes

1. Set `versionName` and increment `versionCode` in `android/app/build.gradle.kts`.
2. Prepare and validate the canonical release notes: `.github/release-notes/vX.Y.Z.json`.
3. Run the Forgejo unsigned-candidate workflow for the exact commit.
4. Verify provenance/SHA-256, then sign in the approval-gated signer.
5. Verify the existing production certificate, push tag `vX.Y.Z`, and publish APK plus `changelog.json` as Forgejo release assets.
6. Publish the external release index last.

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
