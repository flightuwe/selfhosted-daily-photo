# Legacy GitHub Operations

GitHub is no longer the Daily code, CI or release system of record. The historical repository and workflows may remain available only as migration evidence and as a possible one-time source for recovering the existing Android signing identity.

Current operations are documented in `docs/forgejo-operations.md`.

Do not add new GitHub Actions, GHCR dependencies or GitHub release URLs. Do not delete the historical repository until the production Android keystore has been recovered into the protected signer and a signed Forgejo release has been verified on an existing installation.
