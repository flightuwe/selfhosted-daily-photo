# Daily E-mail Support

## Scope

V1 provides optional verified recovery addresses, password reset, newsletter double-opt-in storage, provider-neutral SMTP and Android App Links. Username/password remains the login. V1 does not send newsletter campaigns.

## Safe activation

1. Deploy the additive migration with delivery disabled.
2. Configure `EMAIL_MASTER_KEY_B64`, both approved action hosts, Android package and signing fingerprints.
3. Verify `/.well-known/assetlinks.json` on every public host.
4. In Admin → Konfiguration → E-Mail choose Posteo or enter custom SMTP values.
5. Run “Verbindung testen”, then an actual test message. Only then save with delivery enabled.

Posteo preset: `smtp.posteo.de`, port `587`, mandatory STARTTLS. The preset never fills credentials or overrides the editable sender address.

## Security invariants

- SMTP password and not-yet-delivered action tokens use AES-256-GCM with purpose-separated derived keys.
- Only a SHA-256 token hash remains after SMTP acceptance. Reset tokens last 30 minutes; verification/opt-in tokens last 24 hours.
- Links carry the token only in the URL fragment. Gateway/browser request logs never receive it.
- Reset requests return the same delayed `202` response for unknown, unverified and rate-limited addresses.
- Password changes increment `AuthVersion`, revoke all refresh sessions and invalidate every existing JWT.
- Address changes keep the previous verified address active until confirmation. Newsletter consent is address- and text-version-specific.
- SMTP acceptance is shown as acceptance, never as guaranteed mailbox delivery.

## Rollback

Disable delivery in Admin. Existing verified addresses and consent records remain intact; queued mail is not processed until delivery is re-enabled. Do not remove the master key while encrypted configuration remains in use.
