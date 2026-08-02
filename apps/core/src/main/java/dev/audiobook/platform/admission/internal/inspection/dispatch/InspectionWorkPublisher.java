package dev.audiobook.platform.admission.internal.inspection.dispatch;

import java.util.UUID;

public interface InspectionWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
