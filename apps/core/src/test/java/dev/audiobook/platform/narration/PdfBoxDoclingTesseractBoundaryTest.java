package dev.audiobook.platform.narration;

import dev.audiobook.platform.narration.internal.document.DocumentUnderstandingException;
import dev.audiobook.platform.narration.internal.document.PdfBoxDoclingTesseractBoundaryImpl;
import dev.audiobook.platform.narration.internal.document.PdfDocumentUnderstandingBoundary;
import dev.audiobook.platform.narration.internal.document.PdfNarrationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxDoclingTesseractBoundaryTest {

    @TempDir
    Path scratch;

    @Test
    void pdfBoxTextAndOutlineRemainAuthoritativeWhileDoclingSuppliesLayout() throws Exception {
        Path publication = textPdf("Opening prose exactly.", "Declared chapter");
        Path docling = executable("docling-ok", """
                #!/bin/sh
                printf '%s' '[{"pageNumber":1,"items":[{"role":"heading","text":"Detected heading","confidence":0.97},{"role":"table","text":"Year 2026","confidence":0.91}]}]'
                """);
        var boundary = boundary(docling.toString(), executable("unused-tesseract", "#!/bin/sh\nexit 1\n").toString());

        PdfDocumentUnderstandingBoundary.DocumentProfile profile = boundary.inspect(publication);
        List<PdfDocumentUnderstandingBoundary.PageEvidence> pages = boundary.understandBatch(
                publication, new PdfDocumentUnderstandingBoundary.PageRange(1, 1));

        assertThat(profile.pageCount()).isEqualTo(1);
        assertThat(profile.outline()).containsExactly(
                new PdfDocumentUnderstandingBoundary.OutlineEntry("Declared chapter", 1, "outline:0"));
        assertThat(pages).singleElement().satisfies(page -> {
            assertThat(page.text()).isEqualTo("Opening prose exactly.");
            assertThat(page.textSource()).isEqualTo(PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT);
            assertThat(page.layoutItems()).extracting(PdfDocumentUnderstandingBoundary.LayoutItem::role)
                    .containsExactly(
                            PdfDocumentUnderstandingBoundary.LayoutRole.HEADING,
                            PdfDocumentUnderstandingBoundary.LayoutRole.TABLE);
        });
    }

    @Test
    void failedSinglePageDoclingFallsBackToBoundedLocalTesseract() throws Exception {
        Path publication = blankPdf(new PDRectangle(2_000, 2_000));
        Path docling = executable("docling-fails", "#!/bin/sh\nexit 17\n");
        Path tesseract = executable("tesseract-ok", """
                #!/bin/sh
                dimensions=$(python3 -c 'import struct,sys; data=open(sys.argv[1],"rb").read(24); width,height=struct.unpack(">II",data[16:24]); print(width*height)' "$1")
                [ "$dimensions" -le 1000000 ] || exit 19
                printf '%s' 'OCR recovered prose.'
                """);

        PdfDocumentUnderstandingBoundary.PageEvidence page = boundary(docling.toString(), tesseract.toString())
                .understandPage(publication, 1);

        assertThat(page.text()).isEqualTo("OCR recovered prose.");
        assertThat(page.textSource()).isEqualTo(PdfDocumentUnderstandingBoundary.TextSource.TESSERACT_OCR);
    }

    @Test
    void malformedOrOutOfRangeDoclingEvidenceFailsClosed() throws Exception {
        Path publication = textPdf("Private source.", null);
        Path malformed = executable("docling-malformed", "#!/bin/sh\nprintf '%s' '{not-json}'\n");
        Path outOfRange = executable("docling-range", """
                #!/bin/sh
                printf '%s' '[{"pageNumber":2,"items":[]}]'
                """);

        assertThatThrownBy(() -> boundary(malformed.toString(), "unused").understandBatch(
                        publication, new PdfDocumentUnderstandingBoundary.PageRange(1, 1)))
                .isInstanceOf(DocumentUnderstandingException.class)
                .hasMessage("Docling returned invalid document evidence");
        assertThatThrownBy(() -> boundary(outOfRange.toString(), "unused").understandBatch(
                        publication, new PdfDocumentUnderstandingBoundary.PageRange(1, 1)))
                .isInstanceOf(DocumentUnderstandingException.class)
                .hasMessage("Docling returned evidence outside the requested page range");
    }

    @Test
    void pageLocalRenderOrOcrRejectionBecomesExplicitUnreadableEvidence() throws Exception {
        Path publication = blankPdf(PDRectangle.LETTER);
        Path docling = executable("docling-page-reject", "#!/bin/sh\nexit 17\n");
        Path tesseract = executable("tesseract-page-reject", "#!/bin/sh\nexit 2\n");

        PdfDocumentUnderstandingBoundary.PageEvidence page =
                boundary(docling.toString(), tesseract.toString()).understandPage(publication, 1);

        assertThat(page.readable()).isFalse();
        assertThat(page.textSource()).isEqualTo(PdfDocumentUnderstandingBoundary.TextSource.UNREADABLE);
    }

    private PdfDocumentUnderstandingBoundary boundary(String doclingCommand, String tesseractCommand) {
        PdfNarrationProperties properties = new PdfNarrationProperties(
                2,
                2,
                1,
                1_000_000,
                Duration.ofSeconds(5),
                scratch,
                "java",
                System.getProperty("java.class.path"),
                "dev.audiobook.pdfbox.PdfBoxBoundaryMain",
                256,
                doclingCommand,
                "ignored-docling-script",
                tesseractCommand);
        return new PdfBoxDoclingTesseractBoundaryImpl(properties);
    }

    private Path textPdf(String text, String outlineTitle) throws Exception {
        Path publication = scratch.resolve("text.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            if (outlineTitle != null) {
                PDDocumentOutline outline = new PDDocumentOutline();
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(outlineTitle);
                PDPageFitDestination destination = new PDPageFitDestination();
                destination.setPage(page);
                item.setDestination(destination);
                outline.addLast(item);
                document.getDocumentCatalog().setDocumentOutline(outline);
            }
            document.save(publication.toFile());
        }
        return publication;
    }

    private Path blankPdf(PDRectangle size) throws Exception {
        Path publication = scratch.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(size));
            document.save(publication.toFile());
        }
        return publication;
    }

    private Path executable(String name, String body) throws Exception {
        Path command = scratch.resolve(name);
        Files.writeString(command, body, StandardCharsets.UTF_8);
        assertThat(command.toFile().setExecutable(true)).isTrue();
        return command;
    }
}
