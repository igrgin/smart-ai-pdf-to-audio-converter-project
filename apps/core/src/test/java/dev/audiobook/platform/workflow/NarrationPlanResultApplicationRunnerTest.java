package dev.audiobook.platform.workflow;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.narration.NarrationPlanService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NarrationPlanResultApplicationRunnerTest {

    @Test
    void confirmsRecoveryCandidatesThroughNarrationBeforeApplyingWorkflowResults() {
        AudiobookConversionService conversionService = mock(AudiobookConversionService.class);
        NarrationPlanService narrationPlanService = mock(NarrationPlanService.class);
        UUID candidate = UUID.randomUUID();
        given(conversionService.narrationPlanRecoveryCandidates()).willReturn(List.of(candidate));
        given(narrationPlanService.existingPlanConversionIds(List.of(candidate))).willReturn(List.of(candidate));
        var runner = new NarrationPlanResultApplicationRunner(conversionService, narrationPlanService);

        runner.apply();

        verify(narrationPlanService).existingPlanConversionIds(List.of(candidate));
        verify(conversionService).applyNarrationPlanResults(List.of(candidate));
    }
}
