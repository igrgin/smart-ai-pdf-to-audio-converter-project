package dev.audiobook.platform.entitlement.subscription.service;

import dev.audiobook.platform.entitlement.subscription.*;

public interface DemonstrationSubscriptionProjectorControlService {

    boolean isPaused();

    void pause();

    void resume();
}
