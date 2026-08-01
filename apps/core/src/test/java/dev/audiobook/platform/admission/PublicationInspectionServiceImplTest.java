package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicationInspectionServiceImplTest {

    @TempDir
    Path scratch;

    @Test
    void scansRecognizedBytesBeforeDispatchingToTheMatchingParser() {
        List<String> calls = new ArrayList<>();
        MalwareScanner scanner = publication -> {
            calls.add("malware");
            return MalwareScanner.Result.CLEAN;
        };
        PdfInspectionService pdf = publication -> {
            calls.add("pdf");
            return PdfInspectionService.Result.admissionAllowed();
        };
        EpubInspectionService epub = publication -> {
            calls.add("epub");
            return EpubInspectionService.Result.admissionAllowed();
        };
        PublicationInspectionService inspector =
                new PublicationInspectionServiceImpl(properties(), scanner, pdf, epub);

        PublicationInspectionService.Result result = inspector.inspect(
                new ByteArrayInputStream("%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII)),
                "application/pdf");

        assertThat(result).isEqualTo(PublicationInspectionService.Result.admissionAllowed(
                "application/pdf", "qpdf-pdfbox-v1"));
        assertThat(calls).containsExactly("malware", "pdf");
    }

    @Test
    void refusesMalwareAndScannerFailuresWithoutInvokingAParser() {
        List<String> calls = new ArrayList<>();
        PdfInspectionService pdf = publication -> {
            calls.add("pdf");
            return PdfInspectionService.Result.admissionAllowed();
        };
        EpubInspectionService epub = publication -> {
            calls.add("epub");
            return EpubInspectionService.Result.admissionAllowed();
        };

        assertThat(inspectPdf(MalwareScanner.Result.DETECTED, pdf, epub).reasonCode())
                .isEqualTo("MALWARE_DETECTED");
        assertThat(inspectPdf(MalwareScanner.Result.FAILED, pdf, epub).reasonCode())
                .isEqualTo("MALWARE_SCAN_FAILED");
        assertThat(inspectPdf(MalwareScanner.Result.TIMED_OUT, pdf, epub).reasonCode())
                .isEqualTo("INSPECTION_TIMEOUT");
        assertThat(calls).isEmpty();
    }

    @Test
    void rejectsUnknownBytesAndDeclaredTypeMismatchesWithStableReasons() {
        PublicationInspectionService inspector = new PublicationInspectionServiceImpl(
                properties(),
                publication -> MalwareScanner.Result.CLEAN,
                publication -> PdfInspectionService.Result.admissionAllowed(),
                publication -> EpubInspectionService.Result.admissionAllowed());

        assertThat(inspector.inspect(
                        new ByteArrayInputStream("not-a-publication".getBytes(StandardCharsets.UTF_8)),
                        "application/pdf")
                .reasonCode()).isEqualTo("UNSUPPORTED_PUBLICATION_FORMAT");
        assertThat(inspector.inspect(
                        new ByteArrayInputStream("%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII)),
                        "application/epub+zip")
                .reasonCode()).isEqualTo("MEDIA_TYPE_MISMATCH");
    }

    @Test
    void collapsesParserCrashesToAContentFreeFailure() {
        PublicationInspectionService inspector = new PublicationInspectionServiceImpl(
                properties(),
                publication -> MalwareScanner.Result.CLEAN,
                publication -> { throw new IllegalStateException("private parser diagnostic"); },
                publication -> EpubInspectionService.Result.admissionAllowed());

        assertThat(inspector.inspect(
                        new ByteArrayInputStream("%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII)),
                        "application/pdf")
                .reasonCode()).isEqualTo("INSPECTION_DEPENDENCY_FAILED");
    }

    @Test
    void boundsTheWholeInspectionEvenWhenAParserStopsResponding() throws InterruptedException {
        CountDownLatch parserInterrupted = new CountDownLatch(1);
        PublicationInspectionService inspector = new PublicationInspectionServiceImpl(
                properties(Duration.ofMillis(50)),
                publication -> MalwareScanner.Result.CLEAN,
                publication -> {
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        parserInterrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return PdfInspectionService.Result.admissionAllowed();
                },
                publication -> EpubInspectionService.Result.admissionAllowed());

        assertThat(inspector.inspect(
                        new ByteArrayInputStream("%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII)),
                        "application/pdf")
                .reasonCode()).isEqualTo("INSPECTION_TIMEOUT");
        assertThat(parserInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resultCannotRepresentAnInvalidDecision() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PublicationInspectionService.Result(true, "FAILURE", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new PublicationInspectionService.Result(false, null, "application/pdf", "tool"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PublicationInspectionService.Result inspectPdf(
            MalwareScanner.Result scan,
            PdfInspectionService pdf,
            EpubInspectionService epub) {
        PublicationInspectionService inspector =
                new PublicationInspectionServiceImpl(properties(), publication -> scan, pdf, epub);
        return inspector.inspect(
                new ByteArrayInputStream("%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII)),
                "application/pdf");
    }

    private InspectionProperties properties() {
        return properties(Duration.ofMinutes(9));
    }

    private InspectionProperties properties(Duration runtime) {
        return new InspectionProperties(
                262_144_000L,
                2_000,
                10_000,
                1_073_741_824L,
                100,
                26_214_400L,
                40_000_000L,
                Duration.ofSeconds(30),
                runtime,
                3,
                scratch,
                "clamscan",
                "qpdf");
    }
}
