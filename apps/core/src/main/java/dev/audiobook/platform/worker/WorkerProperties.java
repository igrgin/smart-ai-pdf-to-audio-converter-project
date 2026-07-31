package dev.audiobook.platform.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("worker")
public record WorkerProperties(Stage stage, boolean idle) {

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
