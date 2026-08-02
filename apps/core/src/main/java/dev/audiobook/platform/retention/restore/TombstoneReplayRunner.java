package dev.audiobook.platform.retention.restore;

import dev.audiobook.platform.retention.restore.service.TombstoneReplayService;
import dev.audiobook.platform.retention.restore.persistence.RestoreReplayIncidentPersistence;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "platform.retention.restore-replay-enabled",
        havingValue = "true")
public class TombstoneReplayRunner implements ApplicationRunner {

    private final TombstoneReplayService tombstoneReplayService;
    private final RestoreSafetyGate restoreSafetyGate;
    private final RestoreReplayIncidentPersistence incidentPersistence;

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            tombstoneReplayService.replay();
            incidentPersistence.resolveFailure();
            restoreSafetyGate.markSafe();
        } catch (RuntimeException failure) {
            incidentPersistence.recordFailure();
            throw failure;
        }
    }
}
