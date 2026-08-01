package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NarrationReviewServiceImplTest {

    private static final NarrationPlanService.PlanView PLAN = new NarrationPlanService.PlanView(List.of(
            chapter(0), chapter(1)), false);

    @Test
    void acceptsACompleteBoundedStructuralDecision() {
        var sections = List.of(
                section("second", "Second", List.of(1), item(1)),
                section("first", "First", List.of(0), item(0)));

        assertThat(NarrationReviewServiceImpl.validatedSubmittedSections(PLAN, sections))
                .containsExactlyElementsOf(sections);
    }

    @Test
    void rejectsMissingDuplicateUnknownAndMisplacedSourceContent() {
        assertInvalid(List.of());
        assertInvalid(List.of(section("one", "One", List.of(0), item(0))));
        assertInvalid(List.of(
                section("same", "One", List.of(0), item(0)),
                section("same", "Two", List.of(1), item(1))));
        assertInvalid(List.of(
                section("one", "One", List.of(0, 9), item(0)),
                section("two", "Two", List.of(1), item(1))));
        assertInvalid(List.of(
                section("one", "One", List.of(0), item(0)),
                section("two", "Two", List.of(1), item(0), item(1))));
        assertInvalid(List.of(
                section("one", "One", List.of(0), item(1)),
                section("two", "Two", List.of(1), item(0))));
    }

    @Test
    void rejectsEmptyOrOversizedEditableTextAndMissingTreatments() {
        assertInvalid(List.of(
                section("one", " ", List.of(0), item(0)),
                section("two", "Two", List.of(1), item(1))));
        assertInvalid(List.of(
                section("one", "x".repeat(301), List.of(0), item(0)),
                section("two", "Two", List.of(1), item(1))));
        assertInvalid(List.of(
                section("one", "One", List.of(0), new NarrationReviewService.ReviewItemDecision(0, 0, null, null)),
                section("two", "Two", List.of(1), item(1))));
        assertInvalid(List.of(
                section("one", "One", List.of(0), new NarrationReviewService.ReviewItemDecision(
                        0, 0, NarrationReviewService.Treatment.DESCRIBE, "x".repeat(4_001))),
                section("two", "Two", List.of(1), item(1))));
    }

    private static void assertInvalid(List<NarrationReviewService.SectionDecision> sections) {
        assertThatThrownBy(() -> NarrationReviewServiceImpl.validatedSubmittedSections(PLAN, sections))
                .isInstanceOfSatisfying(NarrationReviewRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(NarrationReviewRejectionReason.INVALID_REVIEW));
    }

    private static NarrationReviewService.SectionDecision section(
            String clientId,
            String title,
            List<Integer> chapterOrdinals,
            NarrationReviewService.ReviewItemDecision... items) {
        return new NarrationReviewService.SectionDecision(
                clientId, title, false, chapterOrdinals, List.of(items));
    }

    private static NarrationReviewService.ReviewItemDecision item(int chapterOrdinal) {
        return new NarrationReviewService.ReviewItemDecision(
                chapterOrdinal, 0, NarrationReviewService.Treatment.READ_VERBATIM, "Snippet");
    }

    private static NarrationPlanService.ChapterView chapter(int ordinal) {
        var provenance = new NarrationPlanService.ProvenanceView(
                "EPUB_XHTML", ordinal, "chapter-" + ordinal + ".xhtml", null, true, 1);
        return new NarrationPlanService.ChapterView(
                ordinal,
                "Chapter " + ordinal,
                provenance,
                List.of(),
                List.of(new NarrationPlanService.ReviewItemView(
                        0, 1, "TABLE", provenance, 1, 1, 1, "READ_VERBATIM", "Snippet", "TABLE_DETECTED")));
    }
}
