package dev.audiobook.platform.narration;

import java.io.InputStream;
import java.util.List;

public interface EpubNarrationPlanInterpreter {

    NarrationPlan interpret(InputStream publication);

    record NarrationPlan(List<Chapter> chapters, List<ReviewItem> reviewItems) {
        public NarrationPlan {
            chapters = List.copyOf(chapters);
            reviewItems = List.copyOf(reviewItems);
        }
    }

    record Chapter(
            int ordinal,
            String title,
            StructuralProvenance provenance,
            List<NormalProse> normalProse,
            List<Gap> gaps) {
        public Chapter {
            normalProse = List.copyOf(normalProse);
            gaps = List.copyOf(gaps);
        }
    }

    record StructuralProvenance(
            String source,
            int spineIndex,
            String spineItem,
            String anchor,
            boolean sourceDeclared,
            double confidence) {
    }

    record NormalProse(String text, StructuralProvenance provenance) {
    }

    record Gap(String sourceUnit, String reasonCode) {
    }

    record ReviewItem(
            int ordinal,
            int chapterOrdinal,
            ReviewItemType type,
            StructuralProvenance provenance,
            double extractionConfidence,
            double classificationConfidence,
            double treatmentConfidence,
            NarrationTreatment recommendedTreatment,
            String narrationSnippet,
            String reasonCode) {
    }

    enum ReviewItemType {
        TABLE,
        FIGURE,
        FORMULA_OR_MATH,
        CODE_OR_PREFORMATTED,
        FOOTNOTE_OR_ENDNOTE,
        SIDEBAR_OR_ASIDE,
        BIBLIOGRAPHY,
        PAGE_HEADER_FOOTER,
        UNREADABLE_SPINE_GAP
    }

    enum NarrationTreatment {
        OMIT,
        READ_VERBATIM,
        SUMMARIZE,
        DESCRIBE
    }
}
