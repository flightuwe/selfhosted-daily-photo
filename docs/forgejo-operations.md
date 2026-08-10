# Daily Forgejo Operations

## Responsibilities

- Public code, issues, CI and releases: `https://code.harzcloud.de/daily-harzcloud/daily`
- Public immutable downloads and signed release index: `https://releases.daily.harzcloud.de/`
- Internal inventory, credentials and operator-only decisions remain in the private cluster Forge.

## CI

`CI` runs Go tests, builds the admin frontend and assembles an Android debug APK on the isolated `daily-build` runner. `Android unsigned candidate` creates an unsigned release APK plus SHA-256 and provenance. The runner has no production, internal-Forge or signing credentials.

Remote actions use fully qualified `https://data.forgejo.org/...` references. Forgejo 11 requires the v3 artifact protocol (or a patched Forgejo v4 action), so the candidate workflow intentionally uses `upload-artifact@v3`.

## Android promotion

1. Require green CI for the exact commit.
2. Build and download the unsigned candidate; verify SHA-256 and provenance.
3. Sign only in the approval-gated signer with the existing production identity.
4. Verify the signing certificate fingerprint before publication.
5. Publish immutable versioned files and the Forgejo release.
6. Replace the release index last and verify it through the external URL.

Never install a new signing key silently: changing the identity breaks in-place updates for existing sideloaded installations.

## Domain migration

New clients use `https://daily.harzcloud.de/api/`. `daily.broutschek.de` remains a compatibility path during the observed migration period. The public website and API share the Harzcloud host through explicit reverse-proxy locations; the admin UI remains protected separately.
