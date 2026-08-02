package dev.audiobook.platform.admission.internal.inspection.intake;

public interface PubSubPushAuthenticator {

    boolean authentic(String token);
}
