package dev.audiobook.platform.narration;

import java.util.List;
import java.util.UUID;

public interface NarrationReviewService {

    ReviewResult submit(ReviewCommand command);

    record ReviewCommand(
            UUID listenerId,
            UUID conversionId,
            ReviewAction action,
            List<SectionDecision> sections,
            long expectedConversionVersion,
            String operationKey) {
        public ReviewCommand {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    record SectionDecision(
            String clientId,
            String title,
            boolean excluded,
            List<Integer> sourceChapterOrdinals,
            List<ReviewItemDecision> reviewItems) {
        public SectionDecision {
            sourceChapterOrdinals = sourceChapterOrdinals == null
                    ? List.of()
                    : List.copyOf(sourceChapterOrdinals);
            reviewItems = reviewItems == null ? List.of() : List.copyOf(reviewItems);
        }
    }

    record ReviewItemDecision(
            int sourceChapterOrdinal,
            int ordinal,
            Treatment treatment,
            String narrationSnippet) {
    }

    record ReviewResult(UUID decisionId, ReviewAction action, long conversionVersion, boolean replayed) {
    }

    enum ReviewAction {
        APPROVE,
        SKIP_OPTIONAL
    }

    enum Treatment {
        OMIT,
        READ_VERBATIM,
        SUMMARIZE,
        DESCRIBE
    }
}
