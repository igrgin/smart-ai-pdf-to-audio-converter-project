package dev.audiobook.platform.worker.internal;

import dev.audiobook.platform.admission.InspectionWorkerService;

import dev.audiobook.platform.generation.AudiobookGenerationWorkerService;
import dev.audiobook.platform.worker.internal.NarrationPlanJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "worker")
public class WorkerEntrypoint implements ApplicationRunner {

    private final WorkerProperties workerProperties;
    private final NarrationPlanJobService narrationPlanJobService;
    private final InspectionWorkerService inspectionWorkerService;
    private final AudiobookGenerationWorkerService audiobookGenerationWorkerService;

    @Override
    public void run(ApplicationArguments arguments) throws InterruptedException {
        if (workerProperties.stage() == null) {
            throw new IllegalStateException("WORKER_STAGE must identify one supported stage");
        }

        log.info("worker_ready stage={}", workerProperties.stage());
        if (workerProperties.stage() == WorkerProperties.Stage.NARRATION_ANALYSIS) {
            if (workerProperties.messageId() != null && workerProperties.workId() != null) {
                narrationPlanJobService.processDelivery(
                        workerProperties.messageId(), workerProperties.workId());
                return;
            }
        }
        do {
            if (workerProperties.stage() == WorkerProperties.Stage.INSPECTION) {
                int processed = inspectionWorkerService.runPending();
                if (processed > 0) {
                    log.info("inspection_batch_complete result_count={}", processed);
                }
            }
            if (workerProperties.stage() == WorkerProperties.Stage.NARRATION_ANALYSIS) {
                narrationPlanJobService.processPending();
            }
            if (workerProperties.stage() == WorkerProperties.Stage.SPEECH) {
                audiobookGenerationWorkerService.generatePending();
            }
            if (workerProperties.stage() == WorkerProperties.Stage.PACKAGING) {
                audiobookGenerationWorkerService.packagePending();
            }
            if (workerProperties.idle()) {
                Thread.sleep(1_000);
            }
        } while (workerProperties.idle());
    }
}
