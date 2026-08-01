package dev.audiobook.platform.workflow.internal;

import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.time.Instant;
import java.util.UUID;

public interface ConversionWorkflowAdministrationService {

    ConversionWorkflowService.StageView checkpoint(StageCheckpoint checkpoint);

    ConversionWorkflowService.StageView repairStage(
            UUID listenerId,
            UUID conversionId,
            ConversionWorkflowService.Stage stage,
            long expectedConversionVersion,
            String idempotencyKey);

    AcceptedResult acceptedResult(UUID listenerId, UUID conversionId, String operationKey);

    ConversionLifecycleService.CancellationResult failTerminal(TerminalFailureCommand command);

    CleanupObligation cleanup(UUID listenerId, UUID conversionId);

    record StageCheckpoint(
            UUID messageId,
            UUID conversionId,
            ConversionWorkflowService.Stage stage,
            String checkpointReference,
            String checkpointDigest) {
    }

    record TerminalFailureCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String failureCode,
            String idempotencyKey) {
    }

    record AcceptedResult(
            UUID acceptedResultId,
            ConversionWorkflowService.Stage stage,
            String operationKey,
            String resultReference,
            String resultDigest,
            boolean providerWork,
            Instant acceptedAt) {
    }

    record CleanupObligation(
            UUID obligationId,
            CleanupState state,
            String reasonCode,
            Instant scheduledAt) {
    }

    enum CleanupState {
        PENDING,
        CLEANING,
        COMPLETED
    }
}
