package dev.audiobook.platform.workflow;

import dev.audiobook.platform.narration.NarrationSelectionService;
import java.util.List;
import java.util.UUID;

public interface AudiobookConversionService {

    void createPreparing(UUID conversionId, UUID listenerId, UUID sourcePublicationId);

    List<AudiobookConversion> conversions(UUID listenerId);

    AudiobookConversion conversion(UUID listenerId, UUID conversionId);

    void markNarrationPlanReady(UUID listenerId, UUID conversionId);

    NarrationSelectionService.GenerationAuthorization beginSpeechGeneration(UUID listenerId, UUID conversionId);

    record AudiobookConversion(
            UUID conversionId,
            ConversionState state,
            String reasonCode,
            List<AllowedAction> allowedActions,
            long version) {
        public AudiobookConversion(UUID conversionId, ConversionState state) {
            this(
                    conversionId,
                    state,
                    switch (state) {
                        case PREPARING -> "NARRATION_PLAN_PENDING";
                        case AWAITING_REVIEW -> "NARRATION_REVIEW_AVAILABLE";
                        case GENERATING -> "GENERATION_IN_PROGRESS";
                    },
                    state == ConversionState.AWAITING_REVIEW
                            ? List.of(AllowedAction.REVIEW_NARRATION_PLAN, AllowedAction.ACCEPT_RECOMMENDATIONS)
                            : List.of(),
                    0);
        }

        public AudiobookConversion {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    enum ConversionState {
        PREPARING,
        AWAITING_REVIEW,
        GENERATING
    }

    enum AllowedAction {
        REVIEW_NARRATION_PLAN,
        ACCEPT_RECOMMENDATIONS
    }
}
