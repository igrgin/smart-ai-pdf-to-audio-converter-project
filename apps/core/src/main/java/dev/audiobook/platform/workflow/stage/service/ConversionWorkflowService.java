package dev.audiobook.platform.workflow.stage.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface ConversionWorkflowService {

    StageView scheduleStage(UUID listenerId, UUID conversionId, Stage stage, int maximumAttempts);

    DeliveryDecision claimDelivery(WorkDelivery delivery);

    boolean claimActive(UUID messageId, UUID conversionId, Stage stage);

    ResultDecision acceptResult(StageResult result);

    StageView failStage(StageFailure failure);

    StageView stage(UUID listenerId, UUID conversionId, Stage stage);

    record WorkDelivery(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            int schemaVersion,
            long expectedConversionVersion,
            String workerId,
            Duration leaseDuration) {}

    record DeliveryDecision(DeliveryDisposition disposition, UUID stageRunId, String reasonCode) {}

    record StageResult(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            String operationKey,
            String resultReference,
            String resultDigest,
            boolean providerWork) {}

    record ResultDecision(
            ResultDisposition disposition, UUID acceptedResultId, String reasonCode) {}

    record StageFailure(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            String failureCode,
            boolean retryable) {}

    record StageView(
            UUID stageRunId,
            Stage stage,
            StageState state,
            int attemptCount,
            int maximumAttempts,
            String leaseOwner,
            Instant leaseExpiresAt,
            String checkpointReference,
            String checkpointDigest) {

        public StageView(
                UUID stageRunId,
                Stage stage,
                StageState state,
                int attemptCount,
                int maximumAttempts,
                String leaseOwner,
                Instant leaseExpiresAt) {
            this(
                    stageRunId,
                    stage,
                    state,
                    attemptCount,
                    maximumAttempts,
                    leaseOwner,
                    leaseExpiresAt,
                    null,
                    null);
        }
    }

    enum Stage {
        INSPECTION,
        EXTRACTION,
        NARRATION_ANALYSIS,
        SPEECH,
        ASSEMBLY,
        PACKAGING,
        FINALIZATION
    }

    enum StageState {
        READY,
        CLAIMED,
        SUCCEEDED,
        FAILED,
        PAUSED,
        CANCELLED
    }

    enum DeliveryDisposition {
        CLAIMED,
        DUPLICATE,
        STALE,
        REJECTED,
        LATE,
        DEAD_LETTERED
    }

    enum ResultDisposition {
        ACCEPTED,
        REPLAYED,
        AMBIGUOUS,
        LATE,
        REJECTED
    }
}
