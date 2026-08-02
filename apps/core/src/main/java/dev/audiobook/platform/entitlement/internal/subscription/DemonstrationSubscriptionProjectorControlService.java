package dev.audiobook.platform.entitlement.internal.subscription;

public interface DemonstrationSubscriptionProjectorControlService {

    boolean isPaused();

    void pause();

    void resume();
}
