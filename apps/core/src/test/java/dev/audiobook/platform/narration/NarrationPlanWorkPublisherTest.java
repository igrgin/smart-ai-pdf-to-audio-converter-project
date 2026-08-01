package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.pubsub.v1.PubsubMessage;
import dev.audiobook.platform.admission.GooglePubSubWorkTransport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NarrationPlanWorkPublisherTest {

    @Test
    void productionPublisherEmitsContentFreeStageCoordinates() {
        GooglePubSubWorkTransport transport = mock(GooglePubSubWorkTransport.class);
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        var publisher = new GooglePubSubNarrationPlanWorkPublisher(transport);

        publisher.publish(messageId, workId);

        ArgumentCaptor<PubsubMessage> message = ArgumentCaptor.forClass(PubsubMessage.class);
        verify(transport).publish(message.capture(), eq("Narration Plan"));
        assertThat(message.getValue().getData().toString(StandardCharsets.UTF_8)).isEqualTo("{}");
        assertThat(message.getValue().getAttributesMap())
                .containsEntry("messageType", "PREPARE_NARRATION_PLAN")
                .containsEntry("schemaVersion", "1")
                .containsEntry("workerStage", "narration-analysis")
                .containsEntry("messageId", messageId.toString())
                .containsEntry("workId", workId.toString());
    }

}
