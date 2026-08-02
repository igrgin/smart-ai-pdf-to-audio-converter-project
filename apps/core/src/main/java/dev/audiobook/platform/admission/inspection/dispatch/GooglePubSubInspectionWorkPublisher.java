package dev.audiobook.platform.admission.inspection.dispatch;

import dev.audiobook.platform.admission.inspection.dispatch.service.*;
import dev.audiobook.platform.worktransport.WorkTransport;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@Profile("prod")
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
@RequiredArgsConstructor
public class GooglePubSubInspectionWorkPublisher implements InspectionWorkPublisher {

    private final WorkTransport transport;

    @Override
    public void publish(UUID messageId, UUID workId) {
        String payload = "{\"messageId\":\"%s\",\"workId\":\"%s\"}".formatted(messageId, workId);
        WorkTransport.WorkMessage message =
                new WorkTransport.WorkMessage(
                        payload,
                        Map.of(
                                "messageType", "INSPECT_SUBMISSION",
                                "schemaVersion", "1",
                                "workerStage", "inspection"));
        transport.publish(message, "inspection");
    }
}
