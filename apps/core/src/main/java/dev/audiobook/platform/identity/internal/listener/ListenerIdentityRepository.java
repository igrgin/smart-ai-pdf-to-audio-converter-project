package dev.audiobook.platform.identity.internal.listener;

import dev.audiobook.platform.identity.internal.signin.ExternalIdentity;
import dev.audiobook.platform.identity.internal.session.ListenerSession;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityRepository {

    Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject);

    Optional<ListenerSession> findById(UUID listenerId);

    ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);
}
