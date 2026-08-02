package dev.audiobook.platform.workflow.narrationanalysis.planning;

import java.io.InputStream;
import java.util.UUID;

/**
 * Creates the result owned by the narration-analysis stage without exposing Narration internals.
 */
public interface NarrationPlanCreator {

    CreatedNarrationPlan create(UUID listenerId, UUID conversionId, InputStream publication);

    record CreatedNarrationPlan(String reference, String digest) {}

    final class SourceTooDamaged extends RuntimeException {

        private final String reasonCode;
        private final int resumeFromPage;
        private final String listenerGuidance;

        public SourceTooDamaged(
                String reasonCode, int resumeFromPage, String listenerGuidance, Throwable cause) {
            super(reasonCode, cause);
            this.reasonCode = reasonCode;
            this.resumeFromPage = resumeFromPage;
            this.listenerGuidance = listenerGuidance;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public int resumeFromPage() {
            return resumeFromPage;
        }

        public String listenerGuidance() {
            return listenerGuidance;
        }
    }
}
