package dev.audiobook.platform.narration.planning.adapter;

import dev.audiobook.platform.narration.SourceTooDamagedException;
import dev.audiobook.platform.narration.planning.service.NarrationPlanService;
import dev.audiobook.platform.workflow.narrationanalysis.planning.NarrationPlanCreator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WorkflowNarrationPlanCreator implements NarrationPlanCreator {

    private final NarrationPlanService narrationPlanService;

    @Override
    public CreatedNarrationPlan create(
            UUID listenerId, UUID conversionId, InputStream publication) {
        try {
            narrationPlanService.prepare(listenerId, conversionId, publication);
            NarrationPlanService.PreparedPlan plan =
                    narrationPlanService.preparedPlan(listenerId, conversionId);
            return new CreatedNarrationPlan(plan.reference(), plan.digest());
        } catch (SourceTooDamagedException exception) {
            throw new SourceTooDamaged(
                    exception.reasonCode(),
                    exception.resumeFromPage(),
                    exception.listenerGuidance(),
                    exception);
        }
    }
}
