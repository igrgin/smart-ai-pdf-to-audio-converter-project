package dev.audiobook.platform.identity.internal.listener;

import dev.audiobook.platform.identity.internal.signin.ExternalIdentity;
import dev.audiobook.platform.identity.internal.session.ListenerSession;

import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityService {

    ListenerSession establish(ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);

    Optional<ListenerSession> find(UUID listenerId);
}
