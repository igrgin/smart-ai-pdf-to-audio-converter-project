package dev.audiobook.platform.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class WorkerEntrypointTest {

    @Test
    void supportedNonIdleStageCompletesItsEntrypoint() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.INSPECTION, false));

        assertThatCode(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void missingStageFailsBeforeTheWorkerCanBecomeReady() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(new WorkerProperties(null, false));

        assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WORKER_STAGE must identify one supported stage");
    }

    @Test
    void idleWorkerPropagatesCancellationByInterruption() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.RECONCILIATION, true));
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }
}
