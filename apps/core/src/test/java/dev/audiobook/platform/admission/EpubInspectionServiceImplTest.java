package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class EpubInspectionServiceImplTest {

    private final EpubInspectionService inspector = new EpubInspectionServiceImpl();

    @Test
    void acceptsAWellFormedEnglishEpubAndRejectsUnsupportedLanguage() throws Exception {
        assertThat(inspect(epub("en", Map.of()))).isEqualTo(EpubInspectionService.Result.admissionAllowed());
        assertThat(inspect(epub("fr", Map.of())).reasonCode()).isEqualTo("UNSUPPORTED_LANGUAGE");
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
        assertThat(inspect(epub("en", Map.of("META-INF/encryption.xml", encryption))).reasonCode())
                .isEqualTo("PROTECTED_PUBLICATION");
        assertThat(inspect(epub("en", Map.of("../escape", "hostile"))).reasonCode())
                .isEqualTo("UNSAFE_ARCHIVE");
    }

    @Test
    void rejectsBytesThatAreNotAnEpubArchive() {
        assertThat(inspect("not-an-epub".getBytes(StandardCharsets.UTF_8)).reasonCode()).isEqualTo("INVALID_EPUB");
    }

    private EpubInspectionService.Result inspect(byte[] bytes) {
        return inspector.inspect(new ByteArrayInputStream(bytes));
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
