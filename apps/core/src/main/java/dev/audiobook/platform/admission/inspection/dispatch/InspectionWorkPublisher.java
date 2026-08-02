package dev.audiobook.platform.admission.inspection.dispatch;

import dev.audiobook.platform.admission.inspection.dispatch.service.*;

import java.util.UUID;

public interface InspectionWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
