# Daily Mirror Policy

## Objective

Keep the public Harzcloud Forgejo authoritative for publishable Daily code while preserving private operational context in the internal cluster Forge.

## Source-of-Truth Split

- Code authoring and merge authority: Forgejo (`daily-harzcloud/daily`)
- Internal knowledge hub and operations continuity: cluster Gitea (`app-daily`)

## Mirror Mechanism

- Public code changes are merged on `code.harzcloud.de`.
- Internal-only runbooks, inventory and secrets stay in the private Forge and must not be copied into the public repository.
- A later read-only archival mirror may copy public `main` inward, but it must never overwrite internal-only documentation.

## Drift Rule

If publishable code differs between public Forgejo and an internal working copy:

1. Treat the public Forgejo `main` history as canonical code truth.
2. Refresh the internal working copy from public Forgejo without overwriting private operator documents.
3. Document incident and recovery in `reports/daily-ops-changelog.md` and mirror status JSON.

## Operational Stamp

Last successful mirror metadata is written by automation into:

- `ops-runbooks/artifacts/daily/mirror-status.json`

Fields:

- UTC timestamp
- Forgejo commit SHA
- Internal mirror run timestamp
- Target Gitea repo

## Current Target

- Base URL default: `http://gitea.internal.local`
- Current writable target: `org-or-user/app-daily`
- Planned final target: `org-or-user/app-daily` after org transfer acceptance

## Cluster Follow-up Tasks

For pending direct Gitea/cluster actions (scheduler ownership, token rotation, monitoring, validation), use:

- `docs/gitea-direct-todo.md`

