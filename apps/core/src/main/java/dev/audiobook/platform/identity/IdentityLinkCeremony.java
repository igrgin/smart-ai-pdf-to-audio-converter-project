package dev.audiobook.platform.identity;

import java.io.Serializable;
import java.util.UUID;

record IdentityLinkCeremony(
        UUID listenerId,
        SignInProvider currentProvider,
        SignInProvider targetProvider,
        Stage stage) implements Serializable {

    static final String SESSION_ATTRIBUTE = IdentityLinkCeremony.class.getName();

    static IdentityLinkCeremony awaitingCurrent(
            UUID listenerId,
            SignInProvider currentProvider,
            SignInProvider targetProvider) {
        return new IdentityLinkCeremony(listenerId, currentProvider, targetProvider, Stage.AWAITING_CURRENT);
    }

    static IdentityLinkCeremony awaitingTarget(
            UUID listenerId,
            SignInProvider currentProvider,
            SignInProvider targetProvider) {
        return new IdentityLinkCeremony(listenerId, currentProvider, targetProvider, Stage.AWAITING_TARGET);
    }

    IdentityLinkCeremony afterCurrentAuthentication() {
        return awaitingTarget(listenerId, currentProvider, targetProvider);
    }

    enum Stage {
        AWAITING_CURRENT,
        AWAITING_TARGET
    }
}
