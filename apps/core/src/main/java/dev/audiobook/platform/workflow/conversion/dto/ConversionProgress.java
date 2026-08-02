package dev.audiobook.platform.workflow.conversion.dto;

import dev.audiobook.platform.narration.planning.service.NarrationPlanService;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;
import dev.audiobook.platform.workflow.lifecycle.service.ConversionLifecycleService;

import java.util.List;
import java.util.UUID;

public record ConversionProgress(
        UUID conversionId,
        AudiobookConversionService.ConversionState state,
        String reasonCode,
        List<AudiobookConversionService.AllowedAction> allowedActions,
        long version,
        AudiobookConversionService.RecoveryDetails recovery,
        NarrationPlanService.PlanView narrationPlan,
        ConversionLifecycleService.PauseDetails pause) {

    public ConversionProgress(
            UUID conversionId,
            AudiobookConversionService.ConversionState state,
            String reasonCode,
            List<AudiobookConversionService.AllowedAction> allowedActions,
            long version,
            AudiobookConversionService.RecoveryDetails recovery,
            NarrationPlanService.PlanView narrationPlan) {
        this(
                conversionId,
                state,
                reasonCode,
                allowedActions,
                version,
                recovery,
                narrationPlan,
                null);
    }
}
