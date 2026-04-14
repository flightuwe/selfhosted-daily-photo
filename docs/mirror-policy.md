# Daily Mirror Policy

## Objective

Keep internal operators fully effective from Forge while preserving GitHub as the write source for Daily code.

## Source-of-Truth Split

- Code authoring and merge authority: GitHub (`flightuwe/selfhosted-daily-photo`)
- Internal knowledge hub and operations continuity: Gitea (`app-daily`)

## Mirror Mechanism

- Workflow: `.github/workflows/sync-gitea-mirror.yml`
- Trigger: every push to `main` (plus manual dispatch)
- Action:
  - push `main` to Gitea mirror target
  - update operational stamp/journal files in Gitea

## Drift Rule

If mismatch occurs between GitHub and Gitea code trees:

1. Treat GitHub commit history as canonical code truth.
2. Re-run mirror sync and verify target branch head.
3. Document incident and recovery in `reports/daily-ops-changelog.md`.

## Operational Stamp

Last successful mirror metadata is appended by automation into:

- `reports/daily-ops-changelog.md`

Fields:

- UTC timestamp
- GitHub commit SHA
- GitHub workflow run URL
- Target Gitea repo

## Current Target

- Base URL default: `http://10.20.10.55:3000`
- Current writable target: `codex-agent/app-daily`
- Planned final target: `praxis-cluster/app-daily` after org transfer acceptance
