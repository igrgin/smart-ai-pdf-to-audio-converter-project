package dev.audiobook.platform.identity;

import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityService {

    ListenerSession establish(ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);

    Optional<ListenerSession> find(UUID listenerId);
}
