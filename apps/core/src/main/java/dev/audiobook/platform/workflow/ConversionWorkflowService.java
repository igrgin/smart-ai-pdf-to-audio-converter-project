package dev.audiobook.platform.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface ConversionWorkflowService {

    StageView scheduleStage(UUID listenerId, UUID conversionId, Stage stage, int maximumAttempts);

    DeliveryDecision claimDelivery(WorkDelivery delivery);

    boolean claimActive(UUID messageId, UUID conversionId, Stage stage);

    StageView checkpoint(StageCheckpoint checkpoint);

    ResultDecision acceptResult(StageResult result);

    StageView failStage(StageFailure failure);

    StageView repairStage(
            UUID listenerId,
            UUID conversionId,
            Stage stage,
            long expectedConversionVersion,
            String idempotencyKey);

    PauseDetails pause(PauseCommand command);

    PauseDetails pauseDetails(UUID listenerId, UUID conversionId);

    StageView resume(ResumeCommand command);

    CancellationResult cancel(CancellationCommand command);

    CancellationResult cancelListener(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey);

    void recordProviderCost(ProviderCost command);

    CancellationResult failTerminal(TerminalFailureCommand command);

    CleanupObligation cleanup(UUID listenerId, UUID conversionId);

    AcceptedResult acceptedResult(UUID listenerId, UUID conversionId, String operationKey);

    StageView stage(UUID listenerId, UUID conversionId, Stage stage);

    record WorkDelivery(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            int schemaVersion,
            long expectedConversionVersion,
            String workerId,
            Duration leaseDuration) {
    }

    record StageCheckpoint(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            String checkpointReference,
            String checkpointDigest) {
    }

    record DeliveryDecision(DeliveryDisposition disposition, UUID stageRunId, String reasonCode) {
    }

    record StageResult(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            String operationKey,
            String resultReference,
            String resultDigest,
            boolean providerWork) {
    }

    record ResultDecision(ResultDisposition disposition, UUID acceptedResultId, String reasonCode) {
    }

    record StageFailure(
            UUID messageId,
            UUID conversionId,
            Stage stage,
            String failureCode,
            boolean retryable) {
    }

    record PauseCommand(
            UUID messageId,
            UUID listenerId,
            UUID conversionId,
            Stage safeResumeStage,
            String reasonCode,
            ResponsibleParty responsibleParty,
            Instant deadline) {
    }

    record ResumeCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey) {
    }

    record CancellationCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            long incurredProviderCostMicros,
            String requestReason,
            String idempotencyKey) {
    }

    record TerminalFailureCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String failureCode,
            long reusableCharacters,
            long incurredProviderCostMicros,
            String idempotencyKey) {
    }

    record ProviderCost(
            UUID listenerId,
            UUID conversionId,
            long incurredProviderCostMicros,
            String evidenceReference,
            String operationKey) {
    }

    record CancellationResult(
            UUID conversionId,
            AudiobookConversionService.ConversionState state,
            String reasonCode,
            long version) {
    }

    record CleanupObligation(
            UUID obligationId,
            CleanupState state,
            String reasonCode,
            Instant scheduledAt) {
    }

    record PauseDetails(
            String reasonCode,
            ResponsibleParty responsibleParty,
            Stage safeResumeStage,
            Instant deadline) {
    }

    record AcceptedResult(
            UUID acceptedResultId,
            Stage stage,
            String operationKey,
            String resultReference,
            String resultDigest,
            boolean providerWork,
            Instant acceptedAt) {
    }

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
            this(stageRunId, stage, state, attemptCount, maximumAttempts,
                    leaseOwner, leaseExpiresAt, null, null);
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

    enum ResponsibleParty {
        LISTENER,
        PLATFORM,
        PROVIDER,
        OPERATOR
    }

    enum CleanupState {
        PENDING,
        CLEANING,
        COMPLETED
    }
}
