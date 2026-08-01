package dev.audiobook.platform.narration;

import java.util.UUID;

public interface NarrationPlanJobService {

    int processPending();

    boolean processDelivery(UUID messageId, UUID workId);
}
