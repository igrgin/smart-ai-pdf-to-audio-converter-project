package dev.audiobook.platform.identity.internal.session;

import dev.audiobook.platform.identity.internal.oidc.ExternalIdentity;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityRepository {

    Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject);

    Optional<ListenerSession> findById(UUID listenerId);

    ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);
}
