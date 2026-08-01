package dev.audiobook.platform.admission;

public interface PubSubPushAuthenticator {

    boolean authentic(String token);
}
