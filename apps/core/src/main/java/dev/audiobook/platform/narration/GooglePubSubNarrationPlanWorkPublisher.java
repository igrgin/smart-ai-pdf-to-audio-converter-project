package dev.audiobook.platform.narration;

import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import dev.audiobook.platform.admission.GooglePubSubWorkTransport;
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

    private final GooglePubSubWorkTransport transport;

    @Override
    public void publish(UUID messageId, UUID workId) {
        PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8("{}"))
                .putAttributes("messageType", "PREPARE_NARRATION_PLAN")
                .putAttributes("schemaVersion", "1")
                .putAttributes("workerStage", "narration-analysis")
                .putAttributes("messageId", messageId.toString())
                .putAttributes("workId", workId.toString())
                .build();
        transport.publish(message, "Narration Plan");
    }
}
