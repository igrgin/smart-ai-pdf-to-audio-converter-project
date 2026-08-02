package dev.audiobook.platform.identity.internal.oidc;

import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.util.Objects;

public record ExternalIdentity(
        URI issuer,
        String subject,
        SignInProvider provider,
        String email,
        String displayName) {

    public ExternalIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(provider, "provider");
        if (!"https".equalsIgnoreCase(issuer.getScheme()) || issuer.getHost() == null) {
            throw new IllegalArgumentException("External identity issuer must be an HTTPS origin");
        }
        if (subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("External identity subject is invalid");
        }
        if (email != null && (email.isBlank() || email.length() > 320)) {
            throw new IllegalArgumentException("Contact email is invalid");
        }
        displayName = displayName == null || displayName.isBlank() ? "Listener" : displayName.strip();
        if (displayName.length() > 200) {
            throw new IllegalArgumentException("Display name is invalid");
        }
    }
}
