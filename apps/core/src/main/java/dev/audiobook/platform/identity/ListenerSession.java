package dev.audiobook.platform.identity;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

public record ListenerSession(
        UUID listenerId,
        String displayName,
        String contactEmail,
        Set<SignInProvider> providers) implements Serializable {

    public ListenerSession {
        providers = Set.copyOf(providers);
    }
}
