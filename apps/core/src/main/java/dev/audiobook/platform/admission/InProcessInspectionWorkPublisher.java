package dev.audiobook.platform.admission;

import dev.audiobook.platform.workflow.InspectionWorkflowService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile("!prod")
public class InProcessInspectionWorkPublisher implements InspectionWorkPublisher {

    private final PublicationSubmissionService submissionService;
    private final InspectionWorkflowService inspectionWorkflowService;
    private final Clock clock;

    @Override
    public void publish(UUID messageId, UUID workId) {
        inspectionWorkflowService.acceptDelivery(messageId, workId);
        submissionService.inspect(new PublicationSubmissionService.InspectionCommand(
                workId,
                "in-process-inspection-worker",
                Instant.now(clock).plusSeconds(60),
                "inspect-" + workId));
    }
}
