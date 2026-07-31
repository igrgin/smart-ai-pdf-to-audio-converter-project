package dev.audiobook.platform.identity;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

interface ListenerIdentityRepository {

    Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject);

    Optional<ListenerSession> findById(UUID listenerId);

    ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);
}
