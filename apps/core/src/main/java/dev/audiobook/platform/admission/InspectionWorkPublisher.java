package dev.audiobook.platform.admission;

import java.util.UUID;

public interface InspectionWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
