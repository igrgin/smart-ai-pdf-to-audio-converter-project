package dev.audiobook.platform.identity.signin;

public interface DeletedExternalIdentityPolicy {

    void requireAllowed(ExternalIdentity externalIdentity);
}
