package dev.audiobook.platform.narration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

public interface PublicationNarrationPlanInterpreter {

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
            ProvenanceSource source,
            @JsonProperty("spineIndex") int sourceIndex,
            @JsonProperty("spineItem") String sourceUnit,
            String anchor,
            boolean sourceDeclared,
            Confidence confidence) {
        public StructuralProvenance {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sourceUnit, "sourceUnit");
            Objects.requireNonNull(confidence, "confidence");
        }
    }

    record NormalProse(int sourceOrdinal, String text, StructuralProvenance provenance) {
    }

    record Gap(String sourceUnit, String reasonCode) {
    }

    record ReviewItem(
            int ordinal,
            int chapterOrdinal,
            int sourceOrdinal,
            ReviewItemType type,
            StructuralProvenance provenance,
            Confidence extractionConfidence,
            Confidence classificationConfidence,
            Confidence treatmentConfidence,
            NarrationTreatment recommendedTreatment,
            String narrationSnippet,
            String reasonCode) {
        public ReviewItem {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(provenance, "provenance");
            Objects.requireNonNull(extractionConfidence, "extractionConfidence");
            Objects.requireNonNull(classificationConfidence, "classificationConfidence");
            Objects.requireNonNull(treatmentConfidence, "treatmentConfidence");
            Objects.requireNonNull(recommendedTreatment, "recommendedTreatment");
            Objects.requireNonNull(reasonCode, "reasonCode");
        }
    }

    record Confidence(@JsonValue double value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public Confidence {
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("Confidence must be between zero and one");
            }
        }
    }

    enum ProvenanceSource {
        EPUB_NAVIGATION,
        EPUB_HEADING,
        EPUB_SPINE,
        EPUB_XHTML,
        PDF_OUTLINE,
        DOCLING_HIERARCHY,
        PDF_LAYOUT,
        PDFBOX_TEXT,
        TESSERACT_OCR
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
        UNCERTAIN_TEXT,
        UNREADABLE_SPINE_GAP,
        UNREADABLE_PAGE_GAP
    }

    enum NarrationTreatment {
        OMIT,
        READ_VERBATIM,
        SUMMARIZE,
        DESCRIBE
    }
}
