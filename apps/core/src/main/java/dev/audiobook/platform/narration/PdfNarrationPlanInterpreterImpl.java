package dev.audiobook.platform.narration;

import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.DocumentProfile;
import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.LayoutItem;
import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.LayoutRole;
import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.OutlineEntry;
import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.PageEvidence;
import dev.audiobook.platform.narration.PdfDocumentUnderstandingBoundary.PageRange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PdfNarrationPlanInterpreterImpl implements PdfNarrationPlanInterpreter {

    private final PdfDocumentUnderstandingBoundary boundary;
    private final PdfNarrationProperties properties;

    @Override
    public NarrationPlan interpret(InputStream publication) {
        Objects.requireNonNull(publication, "publication");
        Path temporary = null;
        try {
            Files.createDirectories(properties.scratchPath());
            temporary = Files.createTempFile(properties.scratchPath(), "publication-", ".pdf");
            Files.copy(publication, temporary, StandardCopyOption.REPLACE_EXISTING);
            DocumentProfile profile = boundary.inspect(temporary);
            return assemble(profile, recoverPages(temporary, profile.pageCount()));
        } catch (SourceTooDamagedException | DocumentUnderstandingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("The PDF Working Asset is unavailable", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Reconciliation removes abandoned opaque worker scratch files.
                }
            }
        }
    }

    private List<PageEvidence> recoverPages(Path publication, int pageCount) {
        List<PageEvidence> pages = new ArrayList<>(pageCount);
        for (int firstPage = 1; firstPage <= pageCount; firstPage += properties.pageBatchSize()) {
            PageRange range = new PageRange(
                    firstPage, Math.min(pageCount, firstPage + properties.pageBatchSize() - 1));
            try {
                List<PageEvidence> batch = boundary.understandBatch(publication, range);
                if (!covers(batch, range)) {
                    throw new DocumentUnderstandingException("Document understanding returned an incomplete page batch");
                }
                pages.addAll(batch.stream().sorted(Comparator.comparingInt(PageEvidence::pageNumber)).toList());
            } catch (RecoverableDocumentUnderstandingException exception) {
                range.pages().mapToObj(page -> boundary.understandPage(publication, page)).forEach(pages::add);
            }
        }
        return pages;
    }

    private static boolean covers(List<PageEvidence> pages, PageRange range) {
        if (pages == null || pages.size() != range.pageCount()) {
            return false;
        }
        return pages.stream().map(PageEvidence::pageNumber).distinct().sorted().toList()
                .equals(range.pages().boxed().toList());
    }

    private NarrationPlan assemble(DocumentProfile profile, List<PageEvidence> pages) {
        List<MutableChapter> chapters = new ArrayList<>();
        List<ReviewItem> reviewItems = new ArrayList<>();
        MutableChapter current = null;
        int unreadablePages = 0;
        int consecutiveUnreadablePages = 0;
        int sourceOrdinal = 0;

        for (PageEvidence page : pages.stream().sorted(Comparator.comparingInt(PageEvidence::pageNumber)).toList()) {
            List<ChapterBoundary> boundaries = chapterBoundaries(profile, page);
            if (current == null && boundaries.isEmpty()) {
                current = fallbackChapter(chapters, page.pageNumber());
            }

            if (!page.readable()) {
                for (ChapterBoundary boundary : boundaries) {
                    current = addChapter(chapters, boundary);
                }
                if (current == null) {
                    current = fallbackChapter(chapters, page.pageNumber());
                }
                unreadablePages++;
                consecutiveUnreadablePages++;
                if (unreadablePages > properties.maximumUnreadablePages()
                        || consecutiveUnreadablePages > properties.maximumConsecutiveUnreadablePages()) {
                    throw new SourceTooDamagedException(page.pageNumber());
                }
                Gap gap = new Gap(sourceUnit(page.pageNumber()), "UNREADABLE_PDF_PAGE");
                current.gaps.add(gap);
                reviewItems.add(unreadableReviewItem(
                        reviewItems.size(), current.ordinal, ++sourceOrdinal, page.pageNumber()));
                continue;
            }

            consecutiveUnreadablePages = 0;
            StructuralProvenance textProvenance = textProvenance(page);
            int cursor = 0;
            for (ChapterBoundary boundary : boundaries) {
                int offset = Math.max(cursor, boundary.textOffset());
                if (offset > cursor && current != null) {
                    current.normalProse.add(
                            new NormalProse(++sourceOrdinal, page.text().substring(cursor, offset), textProvenance));
                }
                current = addChapter(chapters, boundary);
                cursor = offset;
            }
            if (current == null) {
                current = fallbackChapter(chapters, page.pageNumber());
            }
            if (cursor < page.text().length()) {
                current.normalProse.add(
                        new NormalProse(++sourceOrdinal, page.text().substring(cursor), textProvenance));
            }
            for (LayoutItem item : page.layoutItems()) {
                if (item.role() != LayoutRole.HEADING && item.role() != LayoutRole.NORMAL_PROSE) {
                    reviewItems.add(reviewItem(
                            reviewItems.size(), current.ordinal, sourceOrdinal, page.pageNumber(), item));
                }
            }
        }

        return new NarrationPlan(
                chapters.stream().map(MutableChapter::immutable).toList(), reviewItems);
    }

    private static List<ChapterBoundary> chapterBoundaries(DocumentProfile profile, PageEvidence page) {
        List<ChapterBoundary> candidates = new ArrayList<>();
        for (OutlineEntry outline : profile.outline()) {
            if (outline.pageNumber() == page.pageNumber() && outline.title() != null && !outline.title().isBlank()) {
                candidates.add(new ChapterBoundary(
                        outline.title().strip(),
                        lineOffset(page.text(), outline.title()),
                        provenance(
                                ProvenanceSource.PDF_OUTLINE,
                                page.pageNumber(),
                                outline.anchor(),
                                true,
                                1.0),
                        candidates.size()));
            }
        }
        for (LayoutItem heading : page.layoutItems()) {
            if (heading.role() == LayoutRole.HEADING
                    && heading.text() != null
                    && !heading.text().isBlank()
                    && heading.confidence() >= 0.65) {
                ChapterBoundary detected = new ChapterBoundary(
                        heading.text().strip(),
                        textOffset(page.text(), heading.text()),
                        provenance(
                                ProvenanceSource.DOCLING_HIERARCHY,
                                page.pageNumber(),
                                null,
                                false,
                                heading.confidence()),
                        candidates.size());
                int equivalent = equivalentCandidate(candidates, detected.title());
                if (equivalent < 0) {
                    candidates.add(detected);
                } else if (candidates.get(equivalent).provenance().source()
                                != ProvenanceSource.PDF_OUTLINE
                        && detected.provenance().confidence().value()
                                > candidates.get(equivalent).provenance().confidence().value()) {
                    candidates.set(equivalent, new ChapterBoundary(
                            detected.title(),
                            detected.textOffset(),
                            detected.provenance(),
                            candidates.get(equivalent).evidenceOrder()));
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(ChapterBoundary::textOffset)
                        .thenComparingInt(ChapterBoundary::evidenceOrder))
                .toList();
    }

    private static int equivalentCandidate(List<ChapterBoundary> candidates, String title) {
        for (int index = 0; index < candidates.size(); index++) {
            if (equivalentTitle(candidates.get(index).title(), title)) {
                return index;
            }
        }
        return -1;
    }

    private static int textOffset(String text, String title) {
        int offset = text.indexOf(title.strip());
        if (offset >= 0) {
            return offset;
        }
        offset = text.toLowerCase(java.util.Locale.ROOT)
                .indexOf(title.strip().toLowerCase(java.util.Locale.ROOT));
        return Math.max(offset, 0);
    }

    private static int lineOffset(String text, String title) {
        String normalizedText = text.toLowerCase(java.util.Locale.ROOT);
        String normalizedTitle = title.strip().toLowerCase(java.util.Locale.ROOT);
        int candidate = normalizedText.indexOf(normalizedTitle);
        while (candidate >= 0) {
            boolean lineStart = candidate == 0 || normalizedText.charAt(candidate - 1) == '\n';
            int after = candidate + normalizedTitle.length();
            boolean lineEnd = after == normalizedText.length()
                    || normalizedText.charAt(after) == '\n'
                    || Character.isWhitespace(normalizedText.charAt(after));
            if (lineStart && lineEnd) {
                return candidate;
            }
            candidate = normalizedText.indexOf(normalizedTitle, candidate + 1);
        }
        return 0;
    }

    private static boolean equivalentTitle(String left, String right) {
        String normalizedLeft = normalizeTitle(left);
        String normalizedRight = normalizeTitle(right);
        if (normalizedLeft.isEmpty() || normalizedRight.isEmpty()) {
            return false;
        }
        if (normalizedLeft.equals(normalizedRight)
                || normalizedLeft.contains(normalizedRight)
                || normalizedRight.contains(normalizedLeft)) {
            return true;
        }
        java.util.Set<String> leftTokens = new java.util.HashSet<>(List.of(normalizedLeft.split(" ")));
        java.util.Set<String> rightTokens = new java.util.HashSet<>(List.of(normalizedRight.split(" ")));
        long intersection = leftTokens.stream().filter(rightTokens::contains).count();
        long union = java.util.stream.Stream.concat(leftTokens.stream(), rightTokens.stream()).distinct().count();
        return union > 0 && (double) intersection / union >= 0.6;
    }

    private static String normalizeTitle(String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }

    private static MutableChapter addChapter(List<MutableChapter> chapters, ChapterBoundary boundary) {
        MutableChapter chapter = new MutableChapter(
                chapters.size(), boundary.title(), boundary.provenance());
        chapters.add(chapter);
        return chapter;
    }

    private static MutableChapter fallbackChapter(List<MutableChapter> chapters, int pageNumber) {
        MutableChapter chapter = new MutableChapter(
                chapters.size(),
                "Page " + pageNumber,
                provenance(ProvenanceSource.PDF_LAYOUT, pageNumber, null, false, 0.6));
        chapters.add(chapter);
        return chapter;
    }

    private static StructuralProvenance textProvenance(PageEvidence page) {
        ProvenanceSource source = switch (page.textSource()) {
            case PDFBOX_TEXT -> ProvenanceSource.PDFBOX_TEXT;
            case DOCLING_LAYOUT -> ProvenanceSource.PDF_LAYOUT;
            case TESSERACT_OCR -> ProvenanceSource.TESSERACT_OCR;
            case UNREADABLE -> throw new IllegalArgumentException("Unreadable pages have no prose provenance");
        };
        double confidence = switch (page.textSource()) {
            case PDFBOX_TEXT -> 1.0;
            case DOCLING_LAYOUT -> 0.9;
            case TESSERACT_OCR -> 0.75;
            case UNREADABLE -> 0.0;
        };
        return provenance(source, page.pageNumber(), null, false, confidence);
    }

    private static StructuralProvenance provenance(
            ProvenanceSource source,
            int pageNumber,
            String anchor,
            boolean sourceDeclared,
            double confidence) {
        return new StructuralProvenance(
                source,
                pageNumber - 1,
                sourceUnit(pageNumber),
                anchor,
                sourceDeclared,
                new Confidence(confidence));
    }

    private static ReviewItem unreadableReviewItem(
            int ordinal, int chapterOrdinal, int sourceOrdinal, int pageNumber) {
        return new ReviewItem(
                ordinal,
                chapterOrdinal,
                sourceOrdinal,
                ReviewItemType.UNREADABLE_PAGE_GAP,
                provenance(ProvenanceSource.PDF_LAYOUT, pageNumber, null, false, 0.0),
                new Confidence(0.0),
                new Confidence(1.0),
                new Confidence(1.0),
                NarrationTreatment.OMIT,
                null,
                "UNREADABLE_PDF_PAGE");
    }

    private static ReviewItem reviewItem(
            int ordinal, int chapterOrdinal, int sourceOrdinal, int pageNumber, LayoutItem item) {
        ReviewItemType type = switch (item.role()) {
            case TABLE -> ReviewItemType.TABLE;
            case FIGURE -> ReviewItemType.FIGURE;
            case FORMULA -> ReviewItemType.FORMULA_OR_MATH;
            case CODE -> ReviewItemType.CODE_OR_PREFORMATTED;
            case FOOTNOTE -> ReviewItemType.FOOTNOTE_OR_ENDNOTE;
            case SIDEBAR -> ReviewItemType.SIDEBAR_OR_ASIDE;
            case BIBLIOGRAPHY -> ReviewItemType.BIBLIOGRAPHY;
            case PAGE_HEADER_FOOTER -> ReviewItemType.PAGE_HEADER_FOOTER;
            case HEADING, NORMAL_PROSE -> throw new IllegalArgumentException("Normal PDF flow is not a review item");
        };
        NarrationTreatment treatment = switch (type) {
            case FIGURE, FORMULA_OR_MATH -> NarrationTreatment.DESCRIBE;
            case PAGE_HEADER_FOOTER, UNREADABLE_PAGE_GAP, UNREADABLE_SPINE_GAP -> NarrationTreatment.OMIT;
            default -> NarrationTreatment.READ_VERBATIM;
        };
        return new ReviewItem(
                ordinal,
                chapterOrdinal,
                sourceOrdinal,
                type,
                provenance(ProvenanceSource.PDF_LAYOUT, pageNumber, null, false, item.confidence()),
                new Confidence(item.confidence()),
                new Confidence(item.confidence()),
                new Confidence(0.8),
                treatment,
                item.text(),
                "PDF_" + item.role().name());
    }

    private static String sourceUnit(int pageNumber) {
        return "page:" + pageNumber;
    }

    private record ChapterBoundary(
            String title, int textOffset, StructuralProvenance provenance, int evidenceOrder) {
    }

    private static final class MutableChapter {

        private final int ordinal;
        private final String title;
        private final StructuralProvenance provenance;
        private final List<NormalProse> normalProse = new ArrayList<>();
        private final List<Gap> gaps = new ArrayList<>();

        private MutableChapter(int ordinal, String title, StructuralProvenance provenance) {
            this.ordinal = ordinal;
            this.title = title;
            this.provenance = provenance;
        }

        private Chapter immutable() {
            return new Chapter(ordinal, title, provenance, normalProse, gaps);
        }
    }
}
