package dev.audiobook.platform.worker;

import java.util.concurrent.CountDownLatch;
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

    @Override
    public void run(ApplicationArguments arguments) throws InterruptedException {
        if (workerProperties.stage() == null) {
            throw new IllegalStateException("WORKER_STAGE must identify one supported stage");
        }

        log.info("worker_ready stage={}", workerProperties.stage());
        if (workerProperties.idle()) {
            new CountDownLatch(1).await();
        }
    }
}
