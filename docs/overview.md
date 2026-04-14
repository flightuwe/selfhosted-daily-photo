# Daily Knowledge Hub Overview

## Purpose

This document is the canonical high-level entrypoint for Daily in Forge.

- Product: private self-hosted Daily-Moment platform
- Code source of truth: GitHub (`flightuwe/selfhosted-daily-photo`)
- Internal operations and institutional knowledge: Gitea (`app-daily` mirror)

## System Model

Daily is composed of three core parts:

- `backend/`: Go API, scheduler, SQLite persistence, upload handling, admin APIs
- `admin/`: React/Vite admin UI for moderation, trigger runtime, incident export and ops checks
- `android/`: Kotlin/Compose mobile client with release artifacts from GitHub tags

Runtime deployment uses:

- `deploy/portainer-stack.yml` for Synology/Portainer operation
- `deploy/docker-compose.yml` for local or host-level compose operation
- `deploy/nginx` and `deploy/synology` templates for gateway and reverse-proxy integration

## Data Flow

- User/app requests enter through gateway (Nginx) and hit backend APIs
- Backend persists data in `app.db` and uploads under `uploads/`
- Admin APIs expose runtime and forensic status for trigger/scheduler incidents
- Push notifications use optional FCM service-account integration

## Canonical Operational Documents

- GitHub process and CI/CD: `docs/github-operations.md`
- Deploy and migration flow: `docs/deploy-runbook.md`
- Trigger/scheduler incidents: `docs/incident-runbook.md`
- App/server release lifecycle: `docs/release-process.md`
- Secret contract and ownership boundaries: `docs/secrets-contract.md`
- Mirror governance and drift policy: `docs/mirror-policy.md`
- Cluster follow-up checklist for direct Forge work: `docs/gitea-direct-todo.md`
- Ongoing ops history in Forge: `reports/daily-ops-changelog.md`

## Operator Default

When an operator starts from Forge only:

1. Read this file.
2. Read `docs/mirror-policy.md` to confirm mirror state.
3. Follow the specific runbook for deploy, release or incident work.
4. Use GitHub workflow metadata as implementation signal, but keep internal decisions in Forge docs.
