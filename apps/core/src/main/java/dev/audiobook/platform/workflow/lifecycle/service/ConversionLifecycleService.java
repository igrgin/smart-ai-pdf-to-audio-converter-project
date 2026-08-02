package dev.audiobook.platform.workflow.lifecycle.service;

import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import java.time.Instant;
import java.util.UUID;

public interface ConversionLifecycleService {

    PauseDetails pause(PauseCommand command);

    PauseDetails pauseDetails(UUID listenerId, UUID conversionId);

    ConversionWorkflowService.StageView resume(ResumeCommand command);

    CancellationResult cancelListener(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey);

    void recordProviderCost(ProviderCost command);

    record PauseCommand(
            UUID messageId,
            UUID listenerId,
            UUID conversionId,
            ConversionWorkflowService.Stage safeResumeStage,
            String reasonCode,
            ResponsibleParty responsibleParty,
            Instant deadline) {}

    record ResumeCommand(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey) {}

    record ProviderCost(
            UUID listenerId,
            UUID conversionId,
            long incurredProviderCostMicros,
            String evidenceReference,
            String operationKey) {}

    record CancellationResult(
            UUID conversionId,
            AudiobookConversionService.ConversionState state,
            String reasonCode,
            long version) {}

    record PauseDetails(
            String reasonCode,
            ResponsibleParty responsibleParty,
            ConversionWorkflowService.Stage safeResumeStage,
            Instant deadline) {}

    enum ResponsibleParty {
        LISTENER,
        PLATFORM,
        PROVIDER,
        OPERATOR
    }
}
