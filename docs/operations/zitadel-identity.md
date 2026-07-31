# ZITADEL identity boundary

Folio uses one EU-hosted ZITADEL instance as its only OIDC broker. The application never talks to
Google, Apple, or Facebook directly and never receives an upstream provider token in the browser.

## Broker configuration gate

Before an environment can admit Listeners, configure ZITADEL with all of the following:

- an EU-hosted instance and custom HTTPS issuer domain;
- Google, Apple, and Facebook as enabled external identity providers;
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
`ZITADEL_RECOVERY_URI`, `ZITADEL_CLIENT_ID`, and `ZITADEL_CLIENT_SECRET`. Production Terraform reads
the client secret from the existing Secret Manager secret named by `zitadel_client_secret_id`; do not
put it in Terraform variables, repository files, logs, or the browser.

GitHub’s `disposable` environment supplies `ZITADEL_ISSUER`, `ZITADEL_CLIENT_ID`, and
`ZITADEL_CLIENT_SECRET_ID`. The secret itself must exist in the target disposable project before the
full apply, and its latest version must contain only the OIDC client secret.

## Session boundary

OIDC authorized-client state and tokens are serialized only into PostgreSQL-backed Spring sessions.
The browser receives `FOLIO_SESSION` with Secure, HTTP-only, SameSite=Strict attributes and an
independent CSRF value. The session id changes on authentication and periodically, expires after 15
minutes idle, and cannot survive eight hours absolute age. Recovery and logout invalidate local state
before leaving the private surface.

