package dev.audiobook.platform.narration;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface NarrationPlanService {

    void prepare(UUID listenerId, UUID conversionId, InputStream admittedEpub);

    PlanView plan(UUID listenerId, UUID conversionId);

    record PlanView(List<ChapterView> chapters, boolean normalProseEditable) {
        public PlanView {
            chapters = List.copyOf(chapters);
        }
    }

    record ChapterView(
            int ordinal,
            String title,
            ProvenanceView provenance,
            List<GapView> gaps,
            List<ReviewItemView> reviewItems) {
        public ChapterView {
            gaps = List.copyOf(gaps);
            reviewItems = List.copyOf(reviewItems);
        }
    }

    record ProvenanceView(
            String source,
            int spineIndex,
            String spineItem,
            String anchor,
            boolean sourceDeclared,
            double confidence) {
    }

    record GapView(String sourceUnit, String reasonCode) {
    }

    record ReviewItemView(
            int ordinal,
            int sourceOrdinal,
            String type,
            ProvenanceView provenance,
            double extractionConfidence,
            double classificationConfidence,
            double treatmentConfidence,
            String recommendedTreatment,
            String narrationSnippet,
            String reasonCode) {
    }
}
