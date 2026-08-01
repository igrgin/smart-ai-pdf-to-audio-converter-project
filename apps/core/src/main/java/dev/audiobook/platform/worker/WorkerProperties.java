package dev.audiobook.platform.worker;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("worker")
public record WorkerProperties(Stage stage, boolean idle, UUID messageId, UUID workId) {

    public enum Stage {
        INSPECTION,
        EXTRACTION,
        NARRATION_ANALYSIS,
        SPEECH,
        PACKAGING,
        FINALIZATION,
        ERASURE,
        RECONCILIATION
    }
}
