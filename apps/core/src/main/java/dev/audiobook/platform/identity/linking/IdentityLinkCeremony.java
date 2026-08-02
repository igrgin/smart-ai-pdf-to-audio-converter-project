package dev.audiobook.platform.identity.linking;

import dev.audiobook.platform.identity.SignInProvider;

import java.io.Serializable;
import java.util.UUID;

public record IdentityLinkCeremony(
        UUID listenerId, SignInProvider currentProvider, SignInProvider targetProvider, Stage stage)
        implements Serializable {

    public static final String SESSION_ATTRIBUTE = IdentityLinkCeremony.class.getName();

    public static IdentityLinkCeremony awaitingCurrent(
            UUID listenerId, SignInProvider currentProvider, SignInProvider targetProvider) {
        return new IdentityLinkCeremony(
                listenerId, currentProvider, targetProvider, Stage.AWAITING_CURRENT);
    }

    public static IdentityLinkCeremony awaitingTarget(
            UUID listenerId, SignInProvider currentProvider, SignInProvider targetProvider) {
        return new IdentityLinkCeremony(
                listenerId, currentProvider, targetProvider, Stage.AWAITING_TARGET);
    }

    public IdentityLinkCeremony afterCurrentAuthentication() {
        return awaitingTarget(listenerId, currentProvider, targetProvider);
    }

    public enum Stage {
        AWAITING_CURRENT,
        AWAITING_TARGET
    }
}
