package dev.audiobook.platform.worker;

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
    private final InspectionWorkerService inspectionWorkerService;

    @Override
    public void run(ApplicationArguments arguments) throws InterruptedException {
        if (workerProperties.stage() == null) {
            throw new IllegalStateException("WORKER_STAGE must identify one supported stage");
        }

        log.info("worker_ready stage={}", workerProperties.stage());
        do {
            if (workerProperties.stage() == WorkerProperties.Stage.INSPECTION) {
                int processed = inspectionWorkerService.runPending();
                if (processed > 0) {
                    log.info("inspection_batch_complete result_count={}", processed);
                }
            }
            if (workerProperties.idle()) {
                Thread.sleep(1_000);
            }
        } while (workerProperties.idle());
    }
}
