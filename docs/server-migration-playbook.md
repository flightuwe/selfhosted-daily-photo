# Server Migration Playbook (Full Move)

Dieses Playbook deckt den Vollumzug auf einen neuen Host ab (SQLite + Uploads + Secrets + Runtime-Config).

## Ziel
- alter Stack bleibt 48h warm als Rollback
- geplanter Freeze-Cutover mit konsistentem Datenstand
- App kann dank Server-Override v1 auf neue URL umgestellt werden

## Was migriert wird
- `backend-data/app.db`
- `backend-data/uploads/`
- `secrets/` (inkl. FCM Service Account)
- Stack/Env-Config (optional)

## Vor Cutover
1. Neue Zielumgebung mit Portainer/Compose vorbereiten.
2. DNS/TLS fuer neue URL fertigstellen.
3. Android-Version mit URL-Override an Nutzer verteilen.
4. Trockenlauf auf Test-VM machen.

## Backup erzeugen
PowerShell:
```powershell
pwsh scripts/backup-full.ps1 `
  -BackendDataPath "/volume1/docker/selfhosted-daily-photo/backend-data" `
  -SecretsPath "/volume1/docker/selfhosted-daily-photo/secrets" `
  -StackFilePath "/volume1/docker/selfhosted-daily-photo/stack.yml" `
  -EnvFilePath "/volume1/docker/selfhosted-daily-photo/.env" `
  -OutputDir "./migration-backups"
```

Linux:
```bash
./scripts/backup-full.sh \
  /volume1/docker/selfhosted-daily-photo/backend-data \
  /volume1/docker/selfhosted-daily-photo/secrets \
  /volume1/docker/selfhosted-daily-photo/stack.yml \
  /volume1/docker/selfhosted-daily-photo/.env \
  ./migration-backups
```

## Restore auf Zielhost
PowerShell:
```powershell
pwsh scripts/restore-full.ps1 `
  -BundleZip "./migration-backups/daily-migration-<label>.zip" `
  -TargetBackendDataPath "/srv/daily/backend-data" `
  -TargetSecretsPath "/srv/daily/secrets"
```

Linux:
```bash
./scripts/restore-full.sh \
  ./migration-backups/daily-migration-<label>.tar.gz \
  /srv/daily/backend-data \
  /srv/daily/secrets
```

## Smoke-Validierung
PowerShell:
```powershell
pwsh scripts/validate-stack.ps1 -BaseUrl "https://daily.neu.tld" -AdminToken "<admin-token>"
```

Linux:
```bash
./scripts/validate-stack.sh "https://daily.neu.tld" "<admin-token>"
```

## Cutover Tag (Freeze)
1. Scheduler pausieren.
2. Schreibpfad stoppen (kurzes Wartungsfenster).
3. Finales Backup erzeugen.
4. Auf Zielhost restoren.
5. Zielstack starten und validieren.
6. DNS/Proxy auf Ziel umschalten.
7. 2h eng monitoren (Health, Uploads, Feed, Trigger-Audit).

## Rollback (48h warm)
- DNS/Proxy auf alten Stack zurueck.
- Zielstack einfrieren.
- Incident-Export ziehen und Fehlerursache dokumentieren.
