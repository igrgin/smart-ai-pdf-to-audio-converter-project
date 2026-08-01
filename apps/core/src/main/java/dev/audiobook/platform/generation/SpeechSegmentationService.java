package dev.audiobook.platform.generation;

import java.util.List;
import java.util.UUID;

public interface SpeechSegmentationService {

    Manifest segment(SegmentationRequest request);

    String manifestDigest(String policyVersion, List<ManifestEntry> entries);

    record SegmentationRequest(
            UUID conversionId,
            String recipeDigest,
            String policyVersion,
            int maximumCharacters,
            List<ApprovedChapter> chapters) {
        public SegmentationRequest {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
        }
    }

    record ApprovedChapter(int ordinal, String title, List<SpokenUnit> units) {
        public ApprovedChapter {
            units = units == null ? List.of() : List.copyOf(units);
        }
    }

    record SpokenUnit(String text, BoundaryKind boundaryKind) {
    }

    record Manifest(String manifestDigest, List<Segment> segments) {
        public Manifest {
            segments = List.copyOf(segments);
        }
    }

    record Segment(
            String segmentId,
            String operationKey,
            int chapterOrdinal,
            int segmentOrdinal,
            String spokenText,
            String spokenTextDigest,
            BoundaryKind boundaryKind,
            int characterCount) {
    }

    record ManifestEntry(
            int chapterOrdinal,
            int segmentOrdinal,
            String spokenTextDigest,
            BoundaryKind boundaryKind,
            int characterCount) {
    }

    enum BoundaryKind {
        LIMIT_CONTINUATION,
        PARAGRAPH,
        STRUCTURAL_SECTION,
        CHAPTER
    }
}
