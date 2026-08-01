package dev.audiobook.platform.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class WorkerEntrypointTest {

    @Test
    void supportedNonIdleStageCompletesItsEntrypoint() {
        InspectionWorkerService inspectionWorkerService = mock(InspectionWorkerService.class);
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.INSPECTION, false),
                inspectionWorkerService);

        assertThatCode(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
        verify(inspectionWorkerService).runPending();
    }

    @Test
    void missingStageFailsBeforeTheWorkerCanBecomeReady() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(null, false), mock(InspectionWorkerService.class));

        assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WORKER_STAGE must identify one supported stage");
    }

    @Test
    void idleWorkerPropagatesCancellationByInterruption() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.RECONCILIATION, true),
                mock(InspectionWorkerService.class));
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
