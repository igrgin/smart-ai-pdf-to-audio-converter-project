package dev.audiobook.platform.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

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
