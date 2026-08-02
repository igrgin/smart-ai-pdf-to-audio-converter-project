package dev.audiobook.platform.narration.planning.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.narration.SourceTooDamagedException;
import dev.audiobook.platform.narration.planning.service.NarrationPlanService;
import dev.audiobook.platform.workflow.narrationanalysis.planning.NarrationPlanCreator.SourceTooDamaged;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.UUID;

class WorkflowNarrationPlanCreatorTest {

    private final NarrationPlanService narrationPlanService = mock(NarrationPlanService.class);
    private final WorkflowNarrationPlanCreator creator =
            new WorkflowNarrationPlanCreator(narrationPlanService);

    @Test
    void createsWorkflowResultFromPreparedNarrationPlan() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        var publication = new ByteArrayInputStream(new byte[] {1});
        given(narrationPlanService.preparedPlan(listenerId, conversionId))
                .willReturn(new NarrationPlanService.PreparedPlan("plan.json", "a".repeat(64)));

        var result = creator.create(listenerId, conversionId, publication);

        verify(narrationPlanService).prepare(listenerId, conversionId, publication);
        assertThat(result.reference()).isEqualTo("plan.json");
        assertThat(result.digest()).isEqualTo("a".repeat(64));
    }

    @Test
    void translatesNarrationDamageIntoTheWorkflowOwnedFailure() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        var publication = new ByteArrayInputStream(new byte[] {1});
        org.mockito.Mockito.doThrow(new SourceTooDamagedException(17))
                .when(narrationPlanService)
                .prepare(listenerId, conversionId, publication);

        assertThatThrownBy(() -> creator.create(listenerId, conversionId, publication))
                .isInstanceOfSatisfying(
                        SourceTooDamaged.class,
                        failure -> {
                            assertThat(failure.reasonCode()).isEqualTo("SOURCE_TOO_DAMAGED");
                            assertThat(failure.resumeFromPage()).isEqualTo(17);
                            assertThat(failure.listenerGuidance())
                                    .isEqualTo(SourceTooDamagedException.LISTENER_GUIDANCE);
                        });
    }
}
