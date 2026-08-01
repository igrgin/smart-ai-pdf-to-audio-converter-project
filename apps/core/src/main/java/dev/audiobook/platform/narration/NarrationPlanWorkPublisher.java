package dev.audiobook.platform.narration;

import java.util.UUID;

public interface NarrationPlanWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
