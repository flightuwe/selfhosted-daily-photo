# Daily Mirror Policy

## Objective

Keep internal operators fully effective from Forge while preserving GitHub as the write source for Daily code.

## Source-of-Truth Split

- Code authoring and merge authority: GitHub (`flightuwe/selfhosted-daily-photo`)
- Internal knowledge hub and operations continuity: Gitea (`app-daily`)

## Mirror Mechanism

- Canonical path: internal pull job from cluster side
- Script: `ops-runbooks/scripts/daily-github-to-gitea-sync.ps1`
- Runbook: `ops-runbooks/runbooks/daily-github-gitea-mirror.md`
- Trigger recommendation: scheduled task every 5 to 15 minutes on an internal host
- Action:
  - fetch `main` from GitHub
  - push `main` to Gitea mirror target
  - write status to `ops-runbooks/artifacts/daily/mirror-status.json`

## Drift Rule

If mismatch occurs between GitHub and Gitea code trees:

1. Treat GitHub commit history as canonical code truth.
2. Run internal mirror sync and verify target branch head.
3. Document incident and recovery in `reports/daily-ops-changelog.md` and mirror status JSON.

## Operational Stamp

Last successful mirror metadata is written by automation into:

- `ops-runbooks/artifacts/daily/mirror-status.json`

Fields:

- UTC timestamp
- GitHub commit SHA
- Internal mirror run timestamp
- Target Gitea repo

## Current Target

- Base URL default: `http://gitea.internal.local`
- Current writable target: `org-or-user/app-daily`
- Planned final target: `org-or-user/app-daily` after org transfer acceptance

## Cluster Follow-up Tasks

For pending direct Gitea/cluster actions (scheduler ownership, token rotation, monitoring, validation), use:

- `docs/gitea-direct-todo.md`

