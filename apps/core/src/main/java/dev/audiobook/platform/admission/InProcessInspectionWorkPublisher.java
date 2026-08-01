package dev.audiobook.platform.admission;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod")
public class InProcessInspectionWorkPublisher implements InspectionWorkPublisher {

    private final PublicationSubmissionService submissionService;
    private final Clock clock;

    public InProcessInspectionWorkPublisher(PublicationSubmissionService submissionService, Clock clock) {
        this.submissionService = submissionService;
        this.clock = clock;
    }

    @Override
    public void publish(UUID messageId, UUID workId) {
        submissionService.acceptInspectionDelivery(messageId, workId);
        submissionService.inspect(new PublicationSubmissionService.InspectionCommand(
                workId,
                "in-process-inspection-worker",
                Instant.now(clock).plusSeconds(60),
                "inspect-" + workId));
    }
}
