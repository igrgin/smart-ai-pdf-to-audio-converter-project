package dev.audiobook.platform.admission.inspection.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.worktransport.WorkTransport;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

class GooglePubSubInspectionWorkPublisherTest {

    @Test
    void emitsContentFreeInspectionCoordinatesAndStageRouting() {
        WorkTransport transport = mock(WorkTransport.class);
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        var publisher = new GooglePubSubInspectionWorkPublisher(transport);

        publisher.publish(messageId, workId);

        ArgumentCaptor<WorkTransport.WorkMessage> message =
                ArgumentCaptor.forClass(WorkTransport.WorkMessage.class);
        verify(transport).publish(message.capture(), eq("inspection"));
        assertThat(message.getValue().payload())
                .isEqualTo("{\"messageId\":\"%s\",\"workId\":\"%s\"}".formatted(messageId, workId));
        assertThat(message.getValue().attributes())
                .containsEntry("messageType", "INSPECT_SUBMISSION")
                .containsEntry("schemaVersion", "1")
                .containsEntry("workerStage", "inspection");
    }
}
