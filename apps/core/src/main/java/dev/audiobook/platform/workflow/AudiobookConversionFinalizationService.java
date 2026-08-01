package dev.audiobook.platform.workflow;

import java.util.UUID;

public interface AudiobookConversionFinalizationService {

    void beginFinalizing(UUID listenerId, UUID conversionId);

    void lockAndRequireFinalizing(UUID listenerId, UUID conversionId);

    void markFinalized(UUID listenerId, UUID conversionId);
}
