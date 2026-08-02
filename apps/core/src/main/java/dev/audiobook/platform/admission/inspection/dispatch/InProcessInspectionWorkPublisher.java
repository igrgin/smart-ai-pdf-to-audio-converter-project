package dev.audiobook.platform.admission.inspection.dispatch;

import dev.audiobook.platform.admission.inspection.dispatch.service.*;
import dev.audiobook.platform.admission.inspection.work.service.InspectionWorkflowService;

import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
