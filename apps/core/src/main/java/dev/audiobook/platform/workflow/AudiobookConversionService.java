package dev.audiobook.platform.workflow;

import java.util.List;
import java.util.UUID;

public interface AudiobookConversionService {

    void createPreparing(UUID conversionId, UUID listenerId, UUID sourcePublicationId);

    List<AudiobookConversion> conversions(UUID listenerId);

    AudiobookConversion conversion(UUID listenerId, UUID conversionId);

    void markNarrationPlanReady(UUID listenerId, UUID conversionId);

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
                    state == ConversionState.PREPARING ? "NARRATION_PLAN_PENDING" : "NARRATION_REVIEW_AVAILABLE",
                    state == ConversionState.PREPARING
                            ? List.of()
                            : List.of(AllowedAction.REVIEW_NARRATION_PLAN, AllowedAction.ACCEPT_RECOMMENDATIONS),
                    0);
        }

        public AudiobookConversion {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    enum ConversionState {
        PREPARING,
        AWAITING_REVIEW
    }

    enum AllowedAction {
        REVIEW_NARRATION_PLAN,
        ACCEPT_RECOMMENDATIONS
    }
}
