package dev.audiobook.platform.identity.internal.oidc;

public final class IdentityLinkConflictException extends RuntimeException {

    public IdentityLinkConflictException() {
        super("Sign-in method cannot be linked");
    }
}
