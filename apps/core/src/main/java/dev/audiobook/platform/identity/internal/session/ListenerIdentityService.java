package dev.audiobook.platform.identity.internal.session;

import dev.audiobook.platform.identity.internal.oidc.ExternalIdentity;

import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityService {

    ListenerSession establish(ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);

    Optional<ListenerSession> find(UUID listenerId);
}
