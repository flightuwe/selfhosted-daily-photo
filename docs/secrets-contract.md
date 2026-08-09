# Daily Secrets Contract

## Principle

No cleartext secret values are stored in this repository or in Forge runbook copies.

## Secret Classes

Daily operations depend on these secret groups:

- Auth/session: `JWT_SECRET`
- Bootstrap admin credentials
- Firebase service-account JSON (optional, for FCM)
- Android signing material for release workflow
- E-mail encryption: `EMAIL_MASTER_KEY_B64` (32 random bytes, standard Base64)
- SMTP credentials (encrypted at rest by the master key; entered only through admin)

## Storage Boundaries

- Runtime secrets are maintained on deployment targets (`secrets/`, environment config, secret managers).
- Canonical cluster-wide sensitive access references remain in `infra-secrets-docs`.
- This repository only stores variable names, expected format, ownership and rotation procedures.

## Ownership and Rotation

- Platform/Ops owner rotates runtime secrets during cutover or incident response.
- Mobile release owner rotates Android signing and Firebase CI secrets when compromise is suspected.
- Any rotation event must be logged in `reports/daily-ops-changelog.md` with timestamp and reason class.

## Validation Without Disclosure

Secret verification is done by behavior checks, not value disclosure:

- Auth secrets: successful login/token flow
- Firebase secret: successful push dispatch path
- Android signing secrets: successful signed release APK workflow
- E-mail master key: admin reports `passwordConfigured` and a draft SMTP test succeeds; the value itself is never returned

## E-mail Key Rotation

1. Put the new key in `EMAIL_MASTER_KEY_B64` and the old key temporarily in `EMAIL_PREVIOUS_MASTER_KEY_B64`.
2. Restart the backend. Decryptable SMTP/action values are re-encrypted lazily with the new key.
3. After the maximum action lifetime plus queue retry horizon, remove the previous key.
4. A wrong or unavailable key must leave delivery disabled; never replace it speculatively or delete encrypted settings.

## Forbidden Content

Do not add:

- plaintext passwords
- API tokens
- keystores
- base64-encoded secret material
- copied `.env` files with production values
