# Daily Knowledge Hub Overview

## Purpose

This document is the canonical high-level entrypoint for Daily in Forge.

- Product: private self-hosted Daily-Moment platform
- Public code source of truth: Forgejo (`https://code.harzcloud.de/daily-harzcloud/daily`)
- Internal operations and institutional knowledge: cluster Gitea (`app-daily`)

## System Model

Daily is composed of three core parts:

- `backend/`: Go API, scheduler, SQLite persistence, upload handling, admin APIs
- `admin/`: React/Vite admin UI for moderation, trigger runtime, incident export and ops checks
- `android/`: Kotlin/Compose mobile client with release artifacts from Forgejo tags

Current production runtime:

- Primary public endpoint and website: `https://daily.harzcloud.de`
- Compatibility endpoint during migration: `https://daily.broutschek.de`
- Internal target inside cluster/LXC: `http://10.20.10.30:13379`
- Retired migration/source endpoints like `daily.teacloud.synology.me` and `192.168.178.80:13379` are not production anymore

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

- Forgejo process and CI/CD: `docs/forgejo-operations.md`
- Deploy and migration flow: `docs/deploy-runbook.md`
- Trigger/scheduler incidents: `docs/incident-runbook.md`
- App/server release lifecycle: `docs/release-process.md`
- Local Android workstation setup and CI-parity checks: `docs/android-local-toolchain.md`
- Secret contract and ownership boundaries: `docs/secrets-contract.md`
- Mirror governance and drift policy: `docs/mirror-policy.md`
- Cluster follow-up checklist for direct Forge work: `docs/gitea-direct-todo.md`
- Deployment safety checklist for public vs private runtime values: `docs/public-vs-private-config.md`
- Ongoing ops history in Forge: `reports/daily-ops-changelog.md`

## Operator Default

When an operator starts from Forge only:

1. Read this file.
2. Read `docs/mirror-policy.md` to confirm the public/internal source split.
3. Follow the specific runbook for deploy, release or incident work.
4. Use Forgejo workflow metadata as implementation signal, but keep internal decisions in the internal Forge docs.
