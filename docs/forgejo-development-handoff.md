# Forgejo development and server delivery handoff

## Source of truth

`https://code.harzcloud.de/daily-harzcloud/daily` is the only writable code source of truth. Do not publish code, container images or releases through GitHub. External developers use HTTPS because Forgejo SSH port 2222 is not currently exposed through the edge.

## Developer workflow

1. Ask an administrator to create the Forgejo account; public registration is disabled.
2. Create a personal access token on that account and clone `https://code.harzcloud.de/daily-harzcloud/daily.git`.
3. Start from current `origin/main`, create a feature branch and push only that branch.
4. Open a pull request into `main`; backend tests, admin build and Android debug build must pass.
5. Merge only through the protected branch workflow. Never force-push `main` or release tags.

For old GitHub working copies, use a fresh Forgejo clone and cherry-pick only genuine, unpushed development commits. The retired GitHub history diverged through migration-only commits and must never be force-pushed over Forgejo.

## Server image delivery

Every accepted `main` push runs the same CI again and, only after all three CI jobs succeed, builds rootless OCI images on `vm-daily-build-01`:

- `code.harzcloud.de/daily-harzcloud/daily/backend:sha-<full-commit>`
- `code.harzcloud.de/daily-harzcloud/daily/admin:sha-<full-commit>`

Short `sha-<7>` and moving `main` tags are convenience aliases. Production deploys use only digest references resolved from the full 40-character commit tag. The workflow uploads `server-images.json`, digests and checksums as its provenance artifact.

The package publisher is intentionally limited to package writes. It has no production, Proxmox, SSH, Android-signing or release permissions. Pull-request jobs never receive its secrets and never publish images.

## Production deployment

The public build runner cannot reach production. An authorized cluster operator deploys a proven commit through VMID 9204:

```sh
sudo /usr/local/sbin/daily-deploy-forgejo deploy <40-character-main-commit>
sudo /usr/local/sbin/daily-deploy-forgejo status
```

The deploy tool pulls both immutable tags, resolves their OCI digests, stops the backend briefly, creates and integrity-checks a SQLite backup, pins both services by digest, starts them and requires the exact `srv-forgejo-<shortsha>` health version plus a working admin container. A failed health gate restores the previous compose layer and database automatically.

Rollback metadata and database snapshots are under `/opt/daily/deploy-backups`; current deployment metadata is `/var/lib/daily-forgejo-deploy/current.json`. Manual code rollback does not restore the database unless `--restore-db` is explicitly supplied, because doing so can discard user writes.

## Android boundary

The normal runner produces only unsigned Android release candidates. Production signing stays outside the runner and follows the canonical private signing record and `ops-runbooks/runbooks/daily-harzcloud-operations.md`.
