package dev.audiobook.platform.narration.internal.delivery;

import dev.audiobook.platform.narration.NarrationPlanWorkPublisher;

import dev.audiobook.platform.worktransport.WorkTransport;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
@RequiredArgsConstructor
public class GooglePubSubNarrationPlanWorkPublisher implements NarrationPlanWorkPublisher {

    private final WorkTransport transport;

    @Override
    public void publish(UUID messageId, UUID workId) {
        WorkTransport.WorkMessage message = new WorkTransport.WorkMessage("{}", Map.of(
                "messageType", "PREPARE_NARRATION_PLAN",
                "schemaVersion", "1",
                "workerStage", "narration-analysis",
                "messageId", messageId.toString(),
                "workId", workId.toString()));
        transport.publish(message, "Narration Plan");
    }
}
