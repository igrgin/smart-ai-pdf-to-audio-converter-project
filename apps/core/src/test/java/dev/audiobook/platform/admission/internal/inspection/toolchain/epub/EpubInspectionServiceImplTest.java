package dev.audiobook.platform.admission.internal.inspection.toolchain.epub;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionProperties;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EpubInspectionServiceImplTest {

    @TempDir
    Path scratch;

    @Test
    void acceptsAWellFormedEnglishEpubAndRejectsUnsupportedLanguage() throws Exception {
        assertThat(inspect(epub("en", Map.of()), properties(10_000, 1_073_741_824L, 100)))
                .isEqualTo(EpubInspectionService.Result.admissionAllowed());
        assertThat(inspect(epub("fr", Map.of()), properties(10_000, 1_073_741_824L, 100)).reasonCode())
                .isEqualTo("UNSUPPORTED_LANGUAGE");
    }

    @Test
    void rejectsProtectedPublicationsAndUnsafeArchivePaths() throws Exception {
        String encryption = """
                <?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData><EncryptionMethod Algorithm="urn:example:drm"/>
                    <CipherData><CipherReference URI="OPS/chapter.xhtml"/></CipherData>
                  </EncryptedData>
                </encryption>
                """;
        assertThat(inspect(epub("en", Map.of("META-INF/encryption.xml", encryption)),
                        properties(10_000, 1_073_741_824L, 100)).reasonCode())
                .isEqualTo("PROTECTED_PUBLICATION");
        assertThat(inspect(epub("en", Map.of("../escape", "hostile")),
                        properties(10_000, 1_073_741_824L, 100)).reasonCode())
                .isEqualTo("UNSAFE_ARCHIVE");

        String fontObfuscation = """
                <?xml version="1.0"?>
                <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <EncryptedData><EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                    <CipherData><CipherReference URI="OPS/font.ttf"/></CipherData>
                  </EncryptedData>
                </encryption>
                """;
        assertThat(inspect(epub("en", Map.of(
                        "META-INF/encryption.xml", fontObfuscation,
                        "OPS/font.ttf", "synthetic-font")),
                        properties(10_000, 1_073_741_824L, 100)))
                .isEqualTo(EpubInspectionService.Result.admissionAllowed());
    }

    @Test
    void rejectsBytesThatAreNotAnEpubArchive() throws Exception {
        assertThat(inspect("not-an-epub".getBytes(StandardCharsets.UTF_8),
                        properties(10_000, 1_073_741_824L, 100)).reasonCode()).isEqualTo("INVALID_EPUB");
    }

    @Test
    void rejectsEntryExpansionCompressionRatioAndMalformedXhtmlBeforeAdmission() throws Exception {
        byte[] ordinary = epub("en", Map.of("extra.txt", "extra"));
        assertThat(inspect(ordinary, properties(4, 1_073_741_824L, 100)).reasonCode())
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(inspect(ordinary, properties(10_000, 128, 100)).reasonCode())
                .isEqualTo("LIMIT_EXCEEDED");

        String compressible = "a".repeat(20_000);
        assertThat(inspect(epub("en", Map.of("compressible.txt", compressible)),
                        properties(10_000, 1_073_741_824L, 2)).reasonCode())
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(inspect(epub("en", Map.of("OPS/chapter.xhtml", "<html><body>broken")),
                        properties(10_000, 1_073_741_824L, 100)).reasonCode())
                .isEqualTo("INVALID_EPUB");
    }

    private EpubInspectionService.Result inspect(byte[] bytes, InspectionProperties properties) throws Exception {
        Path publication = Files.createTempFile(scratch, "publication-", ".epub");
        Files.write(publication, bytes);
        return new EpubInspectionServiceImpl(properties).inspect(publication);
    }

    private InspectionProperties properties(int entries, long expandedBytes, int ratio) {
        return new InspectionProperties(
                262_144_000L,
                2_000,
                entries,
                expandedBytes,
                ratio,
                26_214_400L,
                40_000_000L,
                Duration.ofSeconds(30),
                Duration.ofMinutes(9),
                3,
                scratch,
                "clamscan",
                "qpdf");
    }

    private static byte[] epub(String language, Map<String, String> extraEntries) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/container.xml", """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
                """);
        entries.put("OPS/package.opf", """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>%s</dc:language></metadata>
                  <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
                """.formatted(language));
        entries.put("OPS/chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>Text</body></html>");
        entries.putAll(extraEntries);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] mediaType = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mediaType);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(mediaType.length);
            mimetype.setCompressedSize(mediaType.length);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(mediaType);
            zip.closeEntry();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
