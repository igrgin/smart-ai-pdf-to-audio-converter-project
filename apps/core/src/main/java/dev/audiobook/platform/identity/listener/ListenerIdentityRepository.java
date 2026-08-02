package dev.audiobook.platform.identity.listener;

import dev.audiobook.platform.identity.listener.service.*;
import dev.audiobook.platform.identity.session.ListenerSession;
import dev.audiobook.platform.identity.signin.ExternalIdentity;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityRepository {

    Optional<ListenerSession> findByExternalIdentity(URI issuer, String subject);

    Optional<ListenerSession> findById(UUID listenerId);

    ListenerSession create(UUID listenerId, ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);
}
