package dev.audiobook.platform.admission.internal.delivery;

import java.util.UUID;

public interface InspectionWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
