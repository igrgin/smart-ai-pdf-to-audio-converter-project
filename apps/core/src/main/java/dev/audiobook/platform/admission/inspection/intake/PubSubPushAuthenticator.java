package dev.audiobook.platform.admission.inspection.intake;

public interface PubSubPushAuthenticator {

    boolean authentic(String token);
}
