package dev.audiobook.platform.workflow;

import dev.audiobook.platform.narration.NarrationPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class NarrationPlanResultApplicationRunner {

    private final AudiobookConversionService conversionService;
    private final NarrationPlanService narrationPlanService;

    @Scheduled(fixedDelayString = "${platform.narration.result-application-delay:1s}")
    public void apply() {
        var candidates = conversionService.narrationPlanRecoveryCandidates();
        conversionService.applyNarrationPlanResults(narrationPlanService.existingPlanConversionIds(candidates));
    }
}
