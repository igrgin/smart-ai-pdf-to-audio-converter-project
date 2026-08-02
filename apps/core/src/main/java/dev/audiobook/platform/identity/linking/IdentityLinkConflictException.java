package dev.audiobook.platform.identity.linking;

public final class IdentityLinkConflictException extends RuntimeException {

    public IdentityLinkConflictException() {
        super("Sign-in method cannot be linked");
    }
}
