package dev.audiobook.platform.workflow.internal;

import dev.audiobook.platform.workflow.AudiobookConversionService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class NarrationPlanResultApplicationRunnerTest {

    @Test
    void appliesOnlyAuthoritativelyAcceptedNarrationResults() {
        AudiobookConversionService conversionService = mock(AudiobookConversionService.class);
        var runner = new NarrationPlanResultApplicationRunner(conversionService);

        runner.apply();

        verify(conversionService).applyNarrationPlanResults();
    }
}
