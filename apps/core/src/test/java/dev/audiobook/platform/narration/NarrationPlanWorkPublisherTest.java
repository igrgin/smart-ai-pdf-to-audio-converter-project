package dev.audiobook.platform.narration;

import dev.audiobook.platform.narration.internal.dispatch.GooglePubSubNarrationPlanWorkPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.worktransport.WorkTransport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NarrationPlanWorkPublisherTest {

    @Test
    void productionPublisherEmitsContentFreeStageCoordinates() {
        WorkTransport transport = mock(WorkTransport.class);
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        var publisher = new GooglePubSubNarrationPlanWorkPublisher(transport);

        publisher.publish(messageId, workId);

        ArgumentCaptor<WorkTransport.WorkMessage> message = ArgumentCaptor.forClass(WorkTransport.WorkMessage.class);
        verify(transport).publish(message.capture(), eq("Narration Plan"));
        assertThat(message.getValue().payload()).isEqualTo("{}");
        assertThat(message.getValue().attributes())
                .containsEntry("messageType", "PREPARE_NARRATION_PLAN")
                .containsEntry("schemaVersion", "1")
                .containsEntry("workerStage", "narration-analysis")
                .containsEntry("messageId", messageId.toString())
                .containsEntry("workId", workId.toString());
    }

}
