package dev.audiobook.platform.workflow;

import dev.audiobook.platform.narration.NarrationSelectionService;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public interface AudiobookConversionService {

    void createPreparing(
            UUID conversionId,
            UUID listenerId,
            UUID sourcePublicationId,
            PreparationReason preparationReason);

    void scheduleNarrationPlan(UUID listenerId, UUID conversionId, UUID submissionId);

    int relayNarrationPlanWork(BiConsumer<UUID, UUID> publisher);

    List<UUID> narrationPlanRecoveryCandidates();

    int applyNarrationPlanResults(List<UUID> planPresentConversionIds);

    List<AudiobookConversion> conversions(UUID listenerId);

    AudiobookConversion conversion(UUID listenerId, UUID conversionId);

    AudiobookConversion resumeNarrationPlan(
            UUID listenerId, UUID conversionId, long expectedVersion, String idempotencyKey);

    NarrationSelectionService.GenerationAuthorization beginSpeechGeneration(UUID listenerId, UUID conversionId);

    record AudiobookConversion(
            UUID conversionId,
            ConversionState state,
            String reasonCode,
            List<AllowedAction> allowedActions,
            long version,
            RecoveryDetails recovery) {
        public AudiobookConversion(UUID conversionId, ConversionState state) {
            this(
                    conversionId,
                    state,
                    switch (state) {
                        case PREPARING -> "NARRATION_PLAN_PENDING";
                        case AWAITING_REVIEW -> "NARRATION_REVIEW_AVAILABLE";
                        case GENERATING -> "GENERATION_IN_PROGRESS";
                        case FINALIZING -> "FINAL_AUDIOBOOK_VALIDATION";
                        case FINALIZED -> "PRIVATE_AUDIOBOOK_AVAILABLE";
                        case PAUSED -> "SOURCE_TOO_DAMAGED";
                        case FAILED -> "CONVERSION_FAILED";
                        case CANCELLED -> "LISTENER_CANCELLED";
                    },
                    state == ConversionState.AWAITING_REVIEW
                            ? List.of(AllowedAction.REVIEW_NARRATION_PLAN, AllowedAction.ACCEPT_RECOMMENDATIONS)
                            : state == ConversionState.PAUSED
                                    ? List.of(AllowedAction.RETRY_NARRATION_PLAN)
                                    : List.of(),
                    0,
                    null);
        }

        public AudiobookConversion(
                UUID conversionId,
                ConversionState state,
                String reasonCode,
                List<AllowedAction> allowedActions,
                long version) {
            this(conversionId, state, reasonCode, allowedActions, version, null);
        }

        public AudiobookConversion {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    enum ConversionState {
        PREPARING,
        AWAITING_REVIEW,
        GENERATING,
        FINALIZING,
        FINALIZED,
        FAILED,
        PAUSED,
        CANCELLED
    }

    enum PreparationReason {
        NARRATION_PLAN_PENDING,
        EXTRACTION_PENDING
    }

    enum AllowedAction {
        REVIEW_NARRATION_PLAN,
        ACCEPT_RECOMMENDATIONS,
        RETRY_NARRATION_PLAN
    }

    record RecoveryDetails(int resumeFromPage, String listenerGuidance) {
    }
}
