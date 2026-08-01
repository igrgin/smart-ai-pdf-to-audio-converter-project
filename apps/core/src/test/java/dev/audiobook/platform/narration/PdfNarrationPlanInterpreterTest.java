package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfNarrationPlanInterpreterTest {

    @TempDir
    Path scratch;

    @Test
    void failedBatchRecoversPageByPageAndPreservesOutlineRankedChapters() {
        var boundary = new FakeBoundary(
                4,
                List.of(
                        new PdfDocumentUnderstandingBoundary.OutlineEntry("Opening", 1, "outline-1"),
                        new PdfDocumentUnderstandingBoundary.OutlineEntry("Evidence", 3, "outline-2")),
                Map.of(
                        1, page(1, "Opening prose.", PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT),
                        2, page(2, "Continuation.", PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT),
                        3, page(3, "Scanned evidence.", PdfDocumentUnderstandingBoundary.TextSource.TESSERACT_OCR),
                        4, page(4, "Closing evidence.", PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT)),
                true);

        PublicationNarrationPlanInterpreter.NarrationPlan plan = interpreter(boundary, 4, 2)
                .interpret(new ByteArrayInputStream("%PDF-bounded".getBytes()));

        assertThat(plan.chapters()).extracting(PublicationNarrationPlanInterpreter.Chapter::title)
                .containsExactly("Opening", "Evidence");
        assertThat(plan.chapters().getFirst().normalProse())
                .extracting(PublicationNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("Opening prose.", "Continuation.");
        assertThat(plan.chapters().get(1).normalProse())
                .extracting(PublicationNarrationPlanInterpreter.NormalProse::text)
                .containsExactly("Scanned evidence.", "Closing evidence.");
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().source())
                .containsOnly(PublicationNarrationPlanInterpreter.ProvenanceSource.PDF_OUTLINE);
        assertThat(plan.chapters().get(1).normalProse().getFirst().provenance().source())
                .isEqualTo(PublicationNarrationPlanInterpreter.ProvenanceSource.TESSERACT_OCR);
        assertThat(boundary.requestedRanges()).allSatisfy(range -> assertThat(range.pageCount()).isLessThanOrEqualTo(2));
    }

    @Test
    void unreadablePagesRemainExplicitWithinTheApprovedDamageLimits() {
        var boundary = new FakeBoundary(
                3,
                List.of(),
                Map.of(
                        1, headingPage(1, "Recovered chapter", "Readable start."),
                        2, unreadablePage(2),
                        3, page(3, "Readable finish.", PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT)),
                false);

        PublicationNarrationPlanInterpreter.NarrationPlan plan = interpreter(boundary, 1, 1)
                .interpret(new ByteArrayInputStream("%PDF-gap".getBytes()));

        assertThat(plan.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.title()).isEqualTo("Recovered chapter");
            assertThat(chapter.gaps()).containsExactly(new PublicationNarrationPlanInterpreter.Gap(
                    "page:2", "UNREADABLE_PDF_PAGE"));
            assertThat(chapter.normalProse())
                    .extracting(PublicationNarrationPlanInterpreter.NormalProse::text)
                    .containsExactly("Readable start.", "Readable finish.");
        });
        assertThat(plan.reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.type())
                    .isEqualTo(PublicationNarrationPlanInterpreter.ReviewItemType.UNREADABLE_PAGE_GAP);
            assertThat(item.reasonCode()).isEqualTo("UNREADABLE_PDF_PAGE");
        });
    }

    @Test
    void partialOutlineAndMultipleSamePageBoundariesMergeWithDetectedHierarchyInOrder() {
        var boundary = new FakeBoundary(
                2,
                List.of(
                        new PdfDocumentUnderstandingBoundary.OutlineEntry("Opening", 1, "outline-1"),
                        new PdfDocumentUnderstandingBoundary.OutlineEntry("Part A", 1, "outline-2")),
                Map.of(
                        1, new PdfDocumentUnderstandingBoundary.PageEvidence(
                                1,
                                "Part A\nExact first section.",
                                PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT,
                                List.of(
                                        new PdfDocumentUnderstandingBoundary.LayoutItem(
                                                PdfDocumentUnderstandingBoundary.LayoutRole.HEADING,
                                                "Chapter: Part A",
                                                0.98),
                                        new PdfDocumentUnderstandingBoundary.LayoutItem(
                                                PdfDocumentUnderstandingBoundary.LayoutRole.HEADING,
                                                "Low-confidence decoration",
                                                0.2))),
                        2, headingPage(2, "Part B", "Part B\nExact second section.")),
                false);

        PublicationNarrationPlanInterpreter.NarrationPlan plan = interpreter(boundary, 2, 1)
                .interpret(new ByteArrayInputStream("%PDF-hierarchy".getBytes()));

        assertThat(plan.chapters())
                .extracting(PublicationNarrationPlanInterpreter.Chapter::title)
                .containsExactly("Opening", "Part A", "Part B");
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().source())
                .containsExactly(
                        PublicationNarrationPlanInterpreter.ProvenanceSource.PDF_OUTLINE,
                        PublicationNarrationPlanInterpreter.ProvenanceSource.PDF_OUTLINE,
                        PublicationNarrationPlanInterpreter.ProvenanceSource.DOCLING_HIERARCHY);
        assertThat(plan.chapters().stream()
                        .flatMap(chapter -> chapter.normalProse().stream())
                        .map(PublicationNarrationPlanInterpreter.NormalProse::text)
                        .reduce("", String::concat))
                .isEqualTo("Part A\nExact first section.Part B\nExact second section.");
    }

    @Test
    void totalDamageBeyondTheApprovedLimitPausesAtASafeResumePage() {
        var boundary = new FakeBoundary(
                4,
                List.of(),
                Map.of(
                        1, headingPage(1, "Start", "Readable."),
                        2, unreadablePage(2),
                        3, page(3, "Recovered.", PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT),
                        4, unreadablePage(4)),
                false);

        assertThatThrownBy(() -> interpreter(boundary, 1, 2)
                        .interpret(new ByteArrayInputStream("%PDF-damaged".getBytes())))
                .isInstanceOf(SourceTooDamagedException.class)
                .satisfies(exception -> {
                    SourceTooDamagedException damaged = (SourceTooDamagedException) exception;
                    assertThat(damaged.resumeFromPage()).isEqualTo(4);
                    assertThat(damaged.reasonCode()).isEqualTo("SOURCE_TOO_DAMAGED");
                    assertThat(damaged.listenerGuidance())
                            .isEqualTo(SourceTooDamagedException.LISTENER_GUIDANCE);
                });
    }

    @Test
    void consecutiveDamageLimitStopsBeforeSilentlySkippingASection() {
        var boundary = new FakeBoundary(
                3,
                List.of(),
                Map.of(1, headingPage(1, "Start", "Readable."), 2, unreadablePage(2), 3, unreadablePage(3)),
                false);

        assertThatThrownBy(() -> interpreter(boundary, 3, 1)
                        .interpret(new ByteArrayInputStream("%PDF-consecutive".getBytes())))
                .isInstanceOf(SourceTooDamagedException.class)
                .satisfies(exception -> assertThat(((SourceTooDamagedException) exception).resumeFromPage())
                        .isEqualTo(3));
    }

    @Test
    void detectedHierarchyDefinesChaptersWithoutRewritingExtractedProse() {
        String sourceProse = "Normal prose remains exactly as extracted -- including punctuation.";
        var boundary = new FakeBoundary(
                2,
                List.of(),
                Map.of(
                        1, headingPage(1, "Detected One", sourceProse),
                        2, headingPage(2, "Detected Two", "Second chapter.")),
                false);

        PublicationNarrationPlanInterpreter.NarrationPlan plan = interpreter(boundary, 1, 1)
                .interpret(new ByteArrayInputStream("%PDF-hierarchy".getBytes()));

        assertThat(plan.chapters()).extracting(PublicationNarrationPlanInterpreter.Chapter::title)
                .containsExactly("Detected One", "Detected Two");
        assertThat(plan.chapters().getFirst().provenance().source())
                .isEqualTo(PublicationNarrationPlanInterpreter.ProvenanceSource.DOCLING_HIERARCHY);
        assertThat(plan.chapters().getFirst().normalProse()).singleElement().satisfies(prose ->
                assertThat(prose.text()).isEqualTo(sourceProse));
    }

    @Test
    void equivalentDetectedHierarchyRetainsTheHighestConfidenceCandidate() {
        var boundary = new FakeBoundary(
                1,
                List.of(),
                Map.of(1, new PdfDocumentUnderstandingBoundary.PageEvidence(
                        1,
                        "Detected chapter\nExact prose.",
                        PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT,
                        List.of(
                                new PdfDocumentUnderstandingBoundary.LayoutItem(
                                        PdfDocumentUnderstandingBoundary.LayoutRole.HEADING,
                                        "Detected chapter",
                                        0.70),
                                new PdfDocumentUnderstandingBoundary.LayoutItem(
                                        PdfDocumentUnderstandingBoundary.LayoutRole.HEADING,
                                        "Detected chapter",
                                        0.97)))),
                false);

        PublicationNarrationPlanInterpreter.NarrationPlan plan = interpreter(boundary, 1, 1)
                .interpret(new ByteArrayInputStream("%PDF-ranked-hierarchy".getBytes()));

        assertThat(plan.chapters()).singleElement().satisfies(chapter -> {
            assertThat(chapter.title()).isEqualTo("Detected chapter");
            assertThat(chapter.provenance().source())
                    .isEqualTo(PublicationNarrationPlanInterpreter.ProvenanceSource.DOCLING_HIERARCHY);
            assertThat(chapter.provenance().confidence().value()).isEqualTo(0.97);
        });
    }

    private PdfNarrationPlanInterpreter interpreter(
            PdfDocumentUnderstandingBoundary boundary,
            int maximumUnreadablePages,
            int maximumConsecutiveUnreadablePages) {
        return new PdfNarrationPlanInterpreterImpl(
                boundary,
                new PdfNarrationProperties(
                        2,
                        maximumUnreadablePages,
                        maximumConsecutiveUnreadablePages,
                        40_000_000,
                        Duration.ofSeconds(30),
                        scratch,
                        "java",
                        System.getProperty("java.class.path"),
                        "dev.audiobook.pdfbox.PdfBoxBoundaryMain",
                        256,
                        "python3",
                        "scripts/docling_extract.py",
                        "tesseract"));
    }

    private static PdfDocumentUnderstandingBoundary.PageEvidence page(
            int pageNumber,
            String text,
            PdfDocumentUnderstandingBoundary.TextSource source) {
        return new PdfDocumentUnderstandingBoundary.PageEvidence(pageNumber, text, source, List.of());
    }

    private static PdfDocumentUnderstandingBoundary.PageEvidence headingPage(
            int pageNumber, String title, String prose) {
        return new PdfDocumentUnderstandingBoundary.PageEvidence(
                pageNumber,
                prose,
                PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT,
                List.of(new PdfDocumentUnderstandingBoundary.LayoutItem(
                        PdfDocumentUnderstandingBoundary.LayoutRole.HEADING, title, 0.95)));
    }

    private static PdfDocumentUnderstandingBoundary.PageEvidence unreadablePage(int pageNumber) {
        return new PdfDocumentUnderstandingBoundary.PageEvidence(
                pageNumber, "", PdfDocumentUnderstandingBoundary.TextSource.UNREADABLE, List.of());
    }

    private static final class FakeBoundary implements PdfDocumentUnderstandingBoundary {

        private final DocumentProfile profile;
        private final Map<Integer, PageEvidence> pages;
        private final boolean failFirstBatch;
        private final List<PageRange> requestedRanges = new ArrayList<>();
        private boolean failed;

        private FakeBoundary(
                int pageCount,
                List<OutlineEntry> outline,
                Map<Integer, PageEvidence> pages,
                boolean failFirstBatch) {
            this.profile = new DocumentProfile(pageCount, outline);
            this.pages = pages;
            this.failFirstBatch = failFirstBatch;
        }

        @Override
        public DocumentProfile inspect(Path publication) {
            return profile;
        }

        @Override
        public List<PageEvidence> understandBatch(Path publication, PageRange range) {
            requestedRanges.add(range);
            if (failFirstBatch && !failed) {
                failed = true;
                throw new RecoverableDocumentUnderstandingException("bounded batch failed");
            }
            return range.pages().mapToObj(pages::get).toList();
        }

        @Override
        public PageEvidence understandPage(Path publication, int pageNumber) {
            requestedRanges.add(new PageRange(pageNumber, pageNumber));
            return pages.get(pageNumber);
        }

        private List<PageRange> requestedRanges() {
            return requestedRanges;
        }
    }
}
