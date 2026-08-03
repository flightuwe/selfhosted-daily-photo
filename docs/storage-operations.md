# Daily Storage Operations

## Scope

Daily media and SQLite data are stored below `/opt/daily/backend-data`; gateway
logs are under `/opt/daily/logs/nginx`. The compose directory is
`/opt/daily/stack`, but it does not contain the persistent application data.

## Nginx Log Retention

Install `deploy/logrotate/daily-nginx` as `/etc/logrotate.d/daily-nginx` in the
Daily LXC. It rotates gateway logs daily, retains 14 compressed rotations and
uses `copytruncate` so the bind-mounted `access.log` stays available to the
backend's forensic reader. The reader safely restarts at byte zero whenever a
rotation makes the file shorter than its saved offset.

Validate without changing files:

```sh
logrotate -d /etc/logrotate.d/daily-nginx
```

Run once after installation and inspect the result:

```sh
logrotate -vf /etc/logrotate.d/daily-nginx
du -sh /opt/daily/logs/nginx
```

## Read-only Storage Report

The `codex-readonly` SSH identity is deliberately a forced-command account.
Its command must execute the report through the privileged node/LXC boundary,
so `docker system df` can read the Docker daemon without granting a shell or a
Docker socket to the account. The report must include:

```sh
df -h /opt/daily/stack
du -sh /opt/daily/backend-data/*
du -sh /opt/daily/logs/*
docker system df
```

The backend system-health API intentionally does not inspect Docker: that data
belongs to the host, while the API runs inside the backend container.

## Storage Thresholds

- 70% used: inspect Docker images/cache and log growth.
- 85% used: rotate logs, prune only unused Docker objects, and verify backups
  are outside the Daily volume.
- 95% used: stop feature rollouts; take a consistent SQLite backup before any
  media or Docker cleanup.

The Admin System Health page reports the mounted data-volume capacity and
breaks down uploads, originals, renditions, SQLite/WAL and backend/gateway
logs. It does not claim to include Docker images or host-level backups.
