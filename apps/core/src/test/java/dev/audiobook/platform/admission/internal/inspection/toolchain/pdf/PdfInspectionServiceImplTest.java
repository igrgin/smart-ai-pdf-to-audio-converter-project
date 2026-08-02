package dev.audiobook.platform.admission.internal.inspection.toolchain.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfInspectionServiceImplTest {

    @TempDir
    Path scratch;

    @Test
    void acceptsAFileBackedPdfWithinThePageAndRenderBounds() throws Exception {
        Path publication = pdf(2, PDRectangle.A4);
        PdfInspectionService inspector =
                new PdfInspectionServiceImpl(properties(2, 40_000_000), ignored -> QpdfValidationService.Result.VALID);

        assertThat(inspector.inspect(publication)).isEqualTo(PdfInspectionService.Result.admissionAllowed());
    }

    @Test
    void classifiesProtectionAndQpdfFailuresWithoutOpeningPdfBox() throws Exception {
        Path invalidBytes = Files.writeString(scratch.resolve("opaque.pdf"), "%PDF-not-actually-a-pdf");

        assertThat(inspect(invalidBytes, QpdfValidationService.Result.ENCRYPTED).reasonCode())
                .isEqualTo("PROTECTED_PUBLICATION");
        assertThat(inspect(invalidBytes, QpdfValidationService.Result.INVALID).reasonCode())
                .isEqualTo("INVALID_PDF");
        assertThat(inspect(invalidBytes, QpdfValidationService.Result.FAILED).reasonCode())
                .isEqualTo("INSPECTION_DEPENDENCY_FAILED");
        assertThat(inspect(invalidBytes, QpdfValidationService.Result.TIMED_OUT).reasonCode())
                .isEqualTo("INSPECTION_TIMEOUT");
    }

    @Test
    void rejectsPageCountAndBoundedRenderingViolations() throws Exception {
        assertThat(new PdfInspectionServiceImpl(
                        properties(2, 40_000_000), ignored -> QpdfValidationService.Result.VALID)
                .inspect(pdf(3, PDRectangle.A4)).reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(new PdfInspectionServiceImpl(
                        properties(2, 1_000), ignored -> QpdfValidationService.Result.VALID)
                .inspect(pdf(1, new PDRectangle(10_000, 10_000))).reasonCode()).isEqualTo("LIMIT_EXCEEDED");
    }

    private PdfInspectionService.Result inspect(Path publication, QpdfValidationService.Result qpdf) {
        return new PdfInspectionServiceImpl(properties(2, 40_000_000), ignored -> qpdf).inspect(publication);
    }

    private Path pdf(int pages, PDRectangle pageSize) throws Exception {
        Path publication = scratch.resolve("publication-" + pages + "-" + pageSize.getWidth() + ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                document.addPage(new PDPage(pageSize));
            }
            document.save(publication.toFile());
        }
        return publication;
    }

    private InspectionProperties properties(int maximumPages, long maximumRenderedPixels) {
        return new InspectionProperties(
                262_144_000L,
                maximumPages,
                10_000,
                1_073_741_824L,
                100,
                26_214_400L,
                maximumRenderedPixels,
                Duration.ofSeconds(30),
                Duration.ofMinutes(9),
                3,
                scratch,
                "clamscan",
                "qpdf");
    }
}
