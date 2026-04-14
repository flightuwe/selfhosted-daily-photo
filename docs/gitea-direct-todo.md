# Gitea Direct TODO (Cluster Follow-up)

This checklist is for cluster-side work directly in Forge/Gitea after the Daily Knowledge Hub rollout.

## 1) Mirror Runtime Ownership

- Decide which internal host runs the pull mirror job permanently.
- Register owner, fallback owner and escalation contact in runbook notes.
- Store this decision in cluster ops documentation.

## 2) Scheduled Execution

- Create scheduled task/timer for:
  - `pwsh ./scripts/daily-github-to-gitea-sync.ps1`
- Recommended interval: every 5 to 15 minutes.
- Ensure task writes status to `ops-runbooks/artifacts/daily/mirror-status.json`.

## 3) Token Placement and Rotation

- Put `DAILY_GH_TOKEN` and `DAILY_GITEA_TOKEN` only on internal execution host(s).
- Record token owner and rotation cadence in `infra-secrets-docs`.
- Verify token scope is minimum required (`read` on GitHub source, `write` on Gitea target).

## 4) Mirror Target Finalization

- Confirm final mirror URL points to org repo:
  - `http://10.20.10.55:3000/praxis-cluster/app-daily.git`
- If any host still points to `codex-agent/app-daily`, migrate configuration.

## 5) Operational Monitoring

- Add monitoring or heartbeat for mirror freshness.
- Alert if:
  - last successful sync age exceeds threshold
  - `source_sha` and `target_sha` mismatch
  - status file reports `failure`

## 6) Bootstrap Discoverability

- Verify new operator bootstrap can find Daily docs in <= 2 clicks from quickstart/runbooks.
- If needed, add additional references from cluster indexes.

## 7) Periodic Validation

- Weekly smoke:
  - run mirror script manually once
  - verify new GitHub commit appears in Gitea
  - confirm status JSON updates
- Log validation result in Daily ops changelog.

## 8) Incident Drill

- Simulate one mirror failure (expired token or temporary connectivity issue).
- Validate recovery path and documentation quality.
- Record findings and improvement actions.
