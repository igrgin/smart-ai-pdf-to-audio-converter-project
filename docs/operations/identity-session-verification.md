# Identity and session verification inventory

Issue #21 is accepted at the observable same-origin HTTP boundary. Focused identity tests exercise
the `ListenerIdentityService` interface; implementation tests exercise the running Spring application,
real Spring Security OAuth callback/token/JWK/user-info boundaries, PostgreSQL, and a deterministic
external broker double.

| Behavior or failure mode | Expected observable result | Coverage |
| --- | --- | --- |
| Google, Apple, or Facebook returns a fresh ZITADEL identity with TOTP MFA | A new server-side session enters the private Library | `BrokerIdentityTest`, `IdentitySessionITest` |
| A broker result omits `mfa`, omits `otp`, or is not freshly authenticated | Authentication fails closed without creating a Listener Identity | `BrokerIdentityTest` |
| The broker token endpoint fails | Authentication returns the bounded failure redirect and creates no authenticated session | `IdentitySessionITest` |
| Apple supplies a relay email or a provider supplies no email | Sign-in succeeds; the value is optional contact metadata | `ListenerIdentityServiceImplTest`, `IdentitySessionITest` |
| Two subjects supply the same email | Two independent Listener Identities are created | `ListenerIdentityServiceImplTest` |
| The same issuer and subject authenticate again | The existing Listener Identity is selected | `ListenerIdentityServiceImplTest` |
| A current Listener links a fresh, interactively authenticated second provider | The issuer/subject pair is attached to that Listener | `ListenerIdentityServiceImplTest`, `IdentitySessionITest` |
| A link is attempted without fresh current authentication or without a pending link ceremony | The request or callback is denied | `IdentitySessionSecurityTest`, `IdentitySessionITest` |
| A new issuer/subject pair is already owned by another Listener | Linking is denied with the same bounded response used for other link failures | `ListenerIdentityServiceImplTest`, `IdentitySessionITest` |
| Recovery is started | The local session is invalidated before redirecting to ZITADEL self-service | `IdentitySessionITest` |
| Logout is requested with valid CSRF and origin evidence | The local session and server-side OIDC state are invalidated | `IdentitySessionITest` |
| Login or age-based rotation occurs | The opaque cookie rotates and tokens never enter a browser response | `IdentitySessionITest` |
| Absolute expiry occurs | The session is invalidated and denied | `SessionLifecycleFilterTest` |
| Idle expiry occurs | Spring Session JDBC makes the inactive session unusable | `IdentitySessionITest` |
| A mutation has missing/invalid CSRF or origin evidence | The same-origin boundary returns a bounded forbidden response | `IdentitySessionSecurityTest` |
| A private response is requested with another or invalidated session | Access is denied without revealing another Listener or external subject | `IdentitySessionITest` |
| A private surface is returned | Strict headers and a per-response CSP nonce are present; no third-party script is permitted | `IdentitySessionSecurityTest`, `scripts/verify-local-environment.sh` |
