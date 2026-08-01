package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.pubsub.v1.PubsubMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GooglePubSubInspectionWorkPublisherTest {

    @Test
    void emitsContentFreeInspectionCoordinatesAndStageRouting() {
        GooglePubSubWorkTransport transport = mock(GooglePubSubWorkTransport.class);
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        var publisher = new GooglePubSubInspectionWorkPublisher(transport);

        publisher.publish(messageId, workId);

        ArgumentCaptor<PubsubMessage> message = ArgumentCaptor.forClass(PubsubMessage.class);
        verify(transport).publish(message.capture(), eq("inspection"));
        assertThat(message.getValue().getData().toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"messageId\":\"%s\",\"workId\":\"%s\"}"
                        .formatted(messageId, workId));
        assertThat(message.getValue().getAttributesMap())
                .containsEntry("messageType", "INSPECT_SUBMISSION")
                .containsEntry("schemaVersion", "1")
                .containsEntry("workerStage", "inspection");
    }
}
