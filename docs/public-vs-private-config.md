# Public vs Private Config Checklist

Use this checklist before every real deploy or release.

## Goal

- Public repo stays sanitized and reusable.
- Real runtime values are injected only in private/internal config paths.

## Must Set Before Real Deploy

1. `PUBLIC_BASE_URL`
- Set to the real public service URL.
- Keep public docs/examples on placeholders only.

2. `ADMIN_BASE_URL`
- Set to the real internal/admin access URL (if different from public URL).

3. `CORS_ORIGINS`
- Include every browser origin that will call backend/admin.
- Include both public and internal admin origins when applicable.

4. `JWT_SECRET`
- Set a long random production secret.
- Never commit real value to repo.

5. `BOOTSTRAP_ADMIN_PASSWORD`
- Set a strong non-default value.
- Never commit real value to repo.

## Optional but Usually Required in Production

6. FCM settings
- `FCM_ENABLED`
- `FCM_PROJECT_ID`
- `FCM_SERVICE_ACCOUNT_FILE`
- Ensure secret file exists on target host and is mounted correctly.

7. `APP_VERSION`
- Set to deploy/build version so clients can validate running server version.

8. Scheduler controls
- `SCHEDULER_ENABLED`
- `SCHEDULER_LEASE_TIMEOUT_SEC`
- Validate scheduler behavior in admin runtime view after deploy.

## Android Release-specific Checks

9. `android/app/build.gradle.kts`
- `versionName` matches release tag (`vX.Y.Z` <-> `X.Y.Z`).
- `versionCode` increases vs previous release.
- `BuildConfig.API_BASE_URL` points to intended environment for this release track.

10. GitHub release secrets
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`

## Post-Deploy Verification (Minimum)

11. Health
- `GET /api/health` returns `ok: true`.

12. Auth and admin
- Admin login works.
- Token flows are healthy.

13. Storage paths
- Upload succeeds and file is retrievable.

14. Runtime controls
- Trigger/scheduler runtime endpoints are healthy.
- No abnormal lock/duplicate trend.

## Governance Rules

- Public repo: placeholders/examples only.
- Internal values: internal env files, secret stores, deployment system variables.
- Never commit:
  - real URLs tied to internal topology
  - real IPs/hostnames for internal services
  - tokens, passwords, keys, private certificates

## Suggested Team Ritual

Before every production deploy, one operator reads this checklist line-by-line and confirms each item.
