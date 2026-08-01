package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

@ActiveProfiles("itest")
class PublicationInspectionToolchainITest {

    private static final ImageFromDockerfile TOOLCHAIN_IMAGE = new ImageFromDockerfile(
                    "folio-inspection-toolchain-itest", false)
            .withDockerfileFromBuilder(builder -> builder
                    .from("alpine:3.22")
                    .run("apk add --no-cache clamav qpdf && freshclam --no-warnings")
                    .cmd("tail", "-f", "/dev/null")
                    .build());

    @TempDir
    Path scratch;

    @Test
    void admitsCleanPdfThroughRealClamavQpdfAndFileBackedPdfbox() throws Exception {
        Path publication = scratch.resolve("publication.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(publication.toFile());
        }

        try (var toolchain = new GenericContainer<>(TOOLCHAIN_IMAGE)
                .withFileSystemBind(scratch.toString(), scratch.toString(), BindMode.READ_ONLY)) {
            toolchain.start();
            Path clamscan = wrapper("clamscan", toolchain.getContainerId(), "/usr/bin/clamscan");
            Path qpdf = wrapper("qpdf", toolchain.getContainerId(), "/usr/bin/qpdf");
            PublicationInspectionService service = service(clamscan, qpdf);

            try (InputStream input = Files.newInputStream(publication)) {
                PublicationInspectionService.Result result = service.inspect(input, "application/pdf");

                assertThat(result.accepted()).isTrue();
                assertThat(result.mediaType()).isEqualTo("application/pdf");
                assertThat(result.toolchainVersion()).isEqualTo("qpdf-pdfbox-v1");
            }
        }
    }

    private PublicationInspectionService service(Path clamscan, Path qpdf) {
        InspectionProperties properties = new InspectionProperties(
                262_144_000L,
                2_000,
                10_000,
                1_073_741_824L,
                100,
                26_214_400L,
                40_000_000L,
                Duration.ofSeconds(30),
                Duration.ofMinutes(9),
                3,
                scratch,
                clamscan.toString(),
                qpdf.toString());
        InspectionCommandRunner runner = new InspectionCommandRunner();
        MalwareScanner malwareScanner = new CommandLineMalwareScannerImpl(properties, runner);
        QpdfValidationService qpdfValidation = new QpdfValidationServiceImpl(properties, runner);
        PdfInspectionService pdfInspection = new PdfInspectionServiceImpl(properties, qpdfValidation);
        return new PublicationInspectionServiceImpl(
                properties, malwareScanner, pdfInspection, new EpubInspectionServiceImpl(properties));
    }

    private Path wrapper(String name, String containerId, String executable) throws Exception {
        Path wrapper = scratch.resolve(name);
        Files.writeString(
                wrapper,
                "#!/bin/sh\nexec /usr/local/bin/docker exec " + containerId + " " + executable + " \"$@\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        return wrapper;
    }
}
