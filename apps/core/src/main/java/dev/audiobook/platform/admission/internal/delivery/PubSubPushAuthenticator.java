package dev.audiobook.platform.admission.internal.delivery;

public interface PubSubPushAuthenticator {

    boolean authentic(String token);
}
