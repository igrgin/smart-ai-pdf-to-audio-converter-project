package dev.audiobook.platform.worktransport.narrationanalysis;

import dev.audiobook.platform.workflow.narrationanalysis.publisher.NarrationAnalysisWorkPublisher;
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
public class GooglePubSubNarrationAnalysisWorkPublisher implements NarrationAnalysisWorkPublisher {

    private final WorkTransport transport;

    @Override
    public void publish(UUID messageId, UUID workId) {
        WorkTransport.WorkMessage message =
                new WorkTransport.WorkMessage(
                        "{}",
                        Map.of(
                                "messageType", "PREPARE_NARRATION_PLAN",
                                "schemaVersion", "1",
                                "workerStage", "narration-analysis",
                                "messageId", messageId.toString(),
                                "workId", workId.toString()));
        transport.publish(message, "Narration Plan");
    }
}
