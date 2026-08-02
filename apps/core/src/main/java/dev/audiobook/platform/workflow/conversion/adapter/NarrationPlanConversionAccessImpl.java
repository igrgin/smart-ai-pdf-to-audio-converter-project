package dev.audiobook.platform.workflow.conversion.adapter;

import dev.audiobook.platform.narration.NarrationPlanConversionAccess;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NarrationPlanConversionAccessImpl implements NarrationPlanConversionAccess {

    private final AudiobookConversionService conversionService;

    @Override
    public boolean awaitingReview(java.util.UUID listenerId, java.util.UUID conversionId) {
        return conversionService.conversion(listenerId, conversionId).state()
                == AudiobookConversionService.ConversionState.AWAITING_REVIEW;
    }

    @Override
    public void requireAccessible(java.util.UUID listenerId, java.util.UUID conversionId) {
        conversionService.conversion(listenerId, conversionId);
    }
}
