package dev.audiobook.platform.worker.internal;

import java.util.UUID;

public interface NarrationPlanJobService {

    int processPending();

    boolean processDelivery(UUID messageId, UUID workId);
}
