package dev.audiobook.platform.identity;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ListenerPrincipal(
        UUID listenerId,
        String displayName,
        String contactEmail,
        Set<SignInProvider> providers,
        SignInProvider currentProvider,
        Instant authenticatedAt) implements Principal, Serializable {

    public ListenerPrincipal {
        providers = Set.copyOf(providers);
    }

    @Override
    public String getName() {
        return listenerId.toString();
    }
}
