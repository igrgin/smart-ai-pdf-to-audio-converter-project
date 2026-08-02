package dev.audiobook.platform.workflow.narrationanalysis.publisher;

import java.util.UUID;

public interface NarrationAnalysisWorkPublisher {

    void publish(UUID messageId, UUID workId);
}
