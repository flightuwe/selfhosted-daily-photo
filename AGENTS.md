# Daily repository instructions

- Forgejo `https://code.harzcloud.de/daily-harzcloud/daily` is the code source of truth. Do not push code, images, tags or releases to GitHub or GHCR.
- Work from current `origin/main` on a feature branch, open a pull request and wait for Forgejo CI. Never force-push `main` or `v*` tags.
- Before changing backend, admin, Android release, deployment or workflows, read `docs/forgejo-development-handoff.md`.
- Backend changes require `cd backend && go test ./...`.
- Admin changes require `cd admin && npm ci && npm run build`.
- Android changes require the relevant Gradle tests/build and must preserve the production application ID and signing compatibility.
- A successful `main` build publishes server images to Forgejo automatically. Developers and coding agents do not receive production deploy, Proxmox, package-publisher or Android-signing secrets.
- Production is deployed only by an authorized cluster operator using the digest-pinning deploy tool documented in `docs/forgejo-development-handoff.md`.
- Release notes remain canonical in `.github/release-notes/vX.Y.Z.json` until that historical directory is migrated deliberately; the directory name does not make GitHub a release target.
