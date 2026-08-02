package dev.audiobook.platform.admission.internal.inspection.dispatch;

import dev.audiobook.platform.admission.internal.inspection.work.InspectionWorkflowService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile("!prod")
public class InProcessInspectionWorkPublisher implements InspectionWorkPublisher {

    private final InspectionWorkflowService inspectionWorkflowService;

    @Override
    public void publish(UUID messageId, UUID workId) {
        inspectionWorkflowService.acceptDelivery(messageId, workId);
    }
}
