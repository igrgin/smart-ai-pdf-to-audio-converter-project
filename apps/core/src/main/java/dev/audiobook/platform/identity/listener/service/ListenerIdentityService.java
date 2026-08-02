package dev.audiobook.platform.identity.listener.service;

import dev.audiobook.platform.identity.listener.*;
import dev.audiobook.platform.identity.session.ListenerSession;
import dev.audiobook.platform.identity.signin.ExternalIdentity;

import java.util.Optional;
import java.util.UUID;

public interface ListenerIdentityService {

    ListenerSession establish(ExternalIdentity externalIdentity);

    ListenerSession link(UUID listenerId, ExternalIdentity externalIdentity);

    Optional<ListenerSession> find(UUID listenerId);
}
