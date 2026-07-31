# ZITADEL identity boundary

Folio uses one EU-hosted ZITADEL instance as its only OIDC broker. The application never talks to
Google, Apple, or Facebook directly and never receives an upstream provider token in the browser.

Operator access is an explicit local allowlist layered on top of the same broker authentication.
After an operator establishes a Listener Identity, set `OPERATOR_LISTENER_IDS` to the
comma-separated local Listener UUIDs allowed to use `/api/v1/operator/**`, redeploy, and have those
operators authenticate again. Social-provider claims never grant operator access on their own.

## Broker configuration gate

Before an environment can admit Listeners, configure ZITADEL with all of the following:

- an EU-hosted instance and custom HTTPS issuer domain;
- Google, Apple, and Facebook as enabled external identity providers; record each ZITADEL provider
  ID as `ZITADEL_GOOGLE_IDP_ID`, `ZITADEL_APPLE_IDP_ID`, and `ZITADEL_FACEBOOK_IDP_ID` so every
  application ceremony is pinned to the provider the Listener selected;
- external identity providers allowed, username/password disabled for this application, automatic
  email/username linking disabled, and interactive account linking enabled;
- TOTP enabled with Force MFA on and “Force MFA for local authenticated users” off, so external
  provider sessions also return `mfa` and `otp` in the OIDC `amr` claim;
- a confidential web OIDC application using authorization code flow, with all three exact callback
  URIs `/login/oauth2/code/google`, `/login/oauth2/code/apple`, and
  `/login/oauth2/code/facebook` on the application origin;
- Login V2/self-service recovery enabled and the default redirect URI set to the Folio origin; and
- a post-external-authentication Action that projects the upstream issuer and subject into the
  `folio_external_issuer` and `folio_external_subject` ID-token claims. No email-based automatic
  linking Action may be installed.

Folio additionally requires PKCE for its confidential client, sends `prompt=login` and `max_age=0`
for every provider ceremony, rejects tokens without fresh TOTP MFA, and treats email only as optional
contact metadata. A projected upstream issuer/subject pair is the external link key; if the projection
is absent, the validated ZITADEL issuer/subject pair remains the link key.

## Runtime values

The core needs `APPLICATION_ORIGIN`, `ZITADEL_ISSUER`, the four ZITADEL endpoint variables,
`ZITADEL_RECOVERY_URI`, `ZITADEL_CLIENT_ID`, the three `ZITADEL_*_IDP_ID` values, and
`ZITADEL_CLIENT_SECRET`. Production Terraform reads
the client secret from the existing Secret Manager secret named by `zitadel_client_secret_id`; do not
put it in Terraform variables, repository files, logs, or the browser.

GitHub’s `disposable` environment supplies `ZITADEL_ISSUER`, `ZITADEL_CLIENT_ID`,
`ZITADEL_CLIENT_SECRET_ID`, and the three provider IDs. The secret itself must exist in the target
disposable project before the full apply, and its latest version must contain only the OIDC client
secret.

## Session boundary

OIDC authorized-client state and tokens are serialized only into PostgreSQL-backed Spring sessions.
The browser receives `FOLIO_SESSION` with Secure, HTTP-only, SameSite=Lax attributes so the
top-level ZITADEL authorization-code callback retains its state, plus an independent CSRF value.
The session id changes on authentication and periodically, expires after 15
minutes idle, and cannot survive eight hours absolute age. Recovery and logout invalidate local state
before leaving the private surface.

Firebase routes `/` and `/index.html` to the core so the application shell and its CSP nonce are
generated together on every response. Version-stable same-origin `/assets/app.js` and
`/assets/app.css` remain immutable Firebase assets; the shell never loads a third-party script.
