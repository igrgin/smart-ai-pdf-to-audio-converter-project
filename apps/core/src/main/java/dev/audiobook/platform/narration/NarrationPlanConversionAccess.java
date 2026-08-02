package dev.audiobook.platform.narration;

import java.util.UUID;

public interface NarrationPlanConversionAccess {

    boolean awaitingReview(UUID listenerId, UUID conversionId);

    void requireAccessible(UUID listenerId, UUID conversionId);
}
