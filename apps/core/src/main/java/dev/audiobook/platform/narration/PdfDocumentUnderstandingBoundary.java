package dev.audiobook.platform.narration;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

public interface PdfDocumentUnderstandingBoundary {

    DocumentProfile inspect(Path publication);

    List<PageEvidence> understandBatch(Path publication, PageRange range);

    PageEvidence understandPage(Path publication, int pageNumber);

    record DocumentProfile(int pageCount, List<OutlineEntry> outline) {
        public DocumentProfile {
            if (pageCount < 1) {
                throw new IllegalArgumentException("A PDF must contain at least one page");
            }
            outline = List.copyOf(outline);
        }
    }

    record OutlineEntry(String title, int pageNumber, String anchor) {
    }

    record PageRange(int firstPage, int lastPage) {
        public PageRange {
            if (firstPage < 1 || lastPage < firstPage) {
                throw new IllegalArgumentException("Invalid PDF page range");
            }
        }

        public int pageCount() {
            return lastPage - firstPage + 1;
        }

        public IntStream pages() {
            return IntStream.rangeClosed(firstPage, lastPage);
        }
    }

    record PageEvidence(int pageNumber, String text, TextSource textSource, List<LayoutItem> layoutItems) {
        public PageEvidence {
            text = text == null ? "" : text;
            layoutItems = List.copyOf(layoutItems);
        }

        public boolean readable() {
            return !text.isBlank();
        }
    }

    record LayoutItem(LayoutRole role, String text, double confidence) {
        public LayoutItem {
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Layout confidence must be between zero and one");
            }
        }
    }

    enum TextSource {
        PDFBOX_TEXT,
        DOCLING_LAYOUT,
        TESSERACT_OCR,
        UNREADABLE
    }

    enum LayoutRole {
        HEADING,
        TABLE,
        FIGURE,
        FORMULA,
        CODE,
        FOOTNOTE,
        SIDEBAR,
        BIBLIOGRAPHY,
        PAGE_HEADER_FOOTER,
        NORMAL_PROSE
    }
}
