package dev.audiobook.platform.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.generation.AudiobookGenerationWorkerService;
import dev.audiobook.platform.narration.NarrationPlanJobService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

class WorkerEntrypointTest {

    @Test
    void supportedNonIdleStageCompletesItsEntrypoint() {
        NarrationPlanJobService narrationPlanJobService = mock(NarrationPlanJobService.class);
        InspectionWorkerService inspectionWorkerService = mock(InspectionWorkerService.class);
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.INSPECTION, false, null, null),
                narrationPlanJobService,
                inspectionWorkerService,
                mock(AudiobookGenerationWorkerService.class));

        assertThatCode(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
        verify(inspectionWorkerService).runPending();
        verify(narrationPlanJobService, never()).processPending();
    }

    @Test
    void narrationAnalysisStageProcessesDurablePendingWork() throws Exception {
        NarrationPlanJobService narrationPlanJobService = mock(NarrationPlanJobService.class);
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.NARRATION_ANALYSIS, false, null, null),
                narrationPlanJobService,
                mock(InspectionWorkerService.class),
                mock(AudiobookGenerationWorkerService.class));

        entrypoint.run(mock(ApplicationArguments.class));

        verify(narrationPlanJobService).processPending();
    }

    @Test
    void narrationDeliveryCoordinatesAreConsumedByTheWorkerEntrypoint() throws Exception {
        NarrationPlanJobService narrationPlanJobService = mock(NarrationPlanJobService.class);
        java.util.UUID messageId = java.util.UUID.randomUUID();
        java.util.UUID workId = java.util.UUID.randomUUID();
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(
                        WorkerProperties.Stage.NARRATION_ANALYSIS, false, messageId, workId),
                narrationPlanJobService,
                mock(InspectionWorkerService.class),
                mock(AudiobookGenerationWorkerService.class));

        entrypoint.run(mock(ApplicationArguments.class));

        verify(narrationPlanJobService).processDelivery(messageId, workId);
        verify(narrationPlanJobService, never()).processPending();
    }

    @Test
    void missingStageFailsBeforeTheWorkerCanBecomeReady() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(null, false, null, null),
                mock(NarrationPlanJobService.class),
                mock(InspectionWorkerService.class),
                mock(AudiobookGenerationWorkerService.class));

        assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WORKER_STAGE must identify one supported stage");
    }

    @Test
    void idleWorkerPropagatesCancellationByInterruption() {
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.RECONCILIATION, true, null, null),
                mock(NarrationPlanJobService.class),
                mock(InspectionWorkerService.class),
                mock(AudiobookGenerationWorkerService.class));
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> entrypoint.run(mock(ApplicationArguments.class)))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void idleNarrationWorkerPollsRepeatedlyUntilInterrupted() throws Exception {
        NarrationPlanJobService narrationPlanJobService = mock(NarrationPlanJobService.class);
        WorkerEntrypoint entrypoint = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.NARRATION_ANALYSIS, true, null, null),
                narrationPlanJobService,
                mock(InspectionWorkerService.class),
                mock(AudiobookGenerationWorkerService.class));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                entrypoint.run(mock(ApplicationArguments.class));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        verify(narrationPlanJobService, timeout(2500).times(2)).processPending();
        worker.interrupt();
        worker.join(1000);

        assertThatCode(() -> {
            if (!(failure.get() instanceof InterruptedException)) {
                throw new AssertionError("Expected polling cancellation to propagate", failure.get());
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void speechAndPackagingStagesAdvanceOnlyTheirGenerationBoundaries() throws Exception {
        AudiobookGenerationWorkerService generationWorker =
                mock(AudiobookGenerationWorkerService.class);
        WorkerEntrypoint speech = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.SPEECH, false, null, null),
                mock(NarrationPlanJobService.class),
                mock(InspectionWorkerService.class),
                generationWorker);
        WorkerEntrypoint packaging = new WorkerEntrypoint(
                new WorkerProperties(WorkerProperties.Stage.PACKAGING, false, null, null),
                mock(NarrationPlanJobService.class),
                mock(InspectionWorkerService.class),
                generationWorker);

        speech.run(mock(ApplicationArguments.class));
        packaging.run(mock(ApplicationArguments.class));

        verify(generationWorker).generatePending();
        verify(generationWorker).packageAndFinalizePending();
    }
}
