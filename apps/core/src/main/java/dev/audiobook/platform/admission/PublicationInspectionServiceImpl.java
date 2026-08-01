package dev.audiobook.platform.admission;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicationInspectionServiceImpl implements PublicationInspectionService {

    static final String EPUB_MEDIA_TYPE = "application/epub+zip";
    static final String PDF_MEDIA_TYPE = "application/pdf";
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EPUB_ENTRY_NAME = "mimetype".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EPUB_MEDIA_TYPE_MAGIC = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    private final InspectionProperties properties;
    private final MalwareScanner malwareScanner;
    private final PdfInspectionService pdfInspectionService;
    private final EpubInspectionService epubInspectionService;

    @Override
    public Result inspect(InputStream publication, String declaredMediaType) {
        var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("publication-inspection-").factory());
        try {
            return executor.submit(() -> inspectWithinBoundary(publication, declaredMediaType))
                    .get(properties.runtime().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            return Result.rejected("INSPECTION_TIMEOUT");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.rejected("INSPECTION_TIMEOUT");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ScratchCleanupException cleanupFailure) {
                throw cleanupFailure;
            }
            return Result.rejected("INSPECTION_DEPENDENCY_FAILED");
        } finally {
            executor.shutdownNow();
        }
    }

    private Result inspectWithinBoundary(InputStream publication, String declaredMediaType) {
        Path temporary = null;
        try {
            Files.createDirectories(properties.scratchPath());
            temporary = Files.createTempFile(properties.scratchPath(), "publication-", ".inspection");
            if (!copyWithinLimit(publication, temporary)) {
                return Result.rejected("LIMIT_EXCEEDED");
            }
            String detected = detect(temporary);
            if (detected == null) {
                return Result.rejected("UNSUPPORTED_PUBLICATION_FORMAT");
            }
            if (declaredMediaType == null
                    || !detected.equals(declaredMediaType.strip().toLowerCase(Locale.ROOT))) {
                return Result.rejected("MEDIA_TYPE_MISMATCH");
            }
            Result scanFailure = scanFailure(malwareScanner.scan(temporary));
            if (scanFailure != null) {
                return scanFailure;
            }
            if (PDF_MEDIA_TYPE.equals(detected)) {
                PdfInspectionService.Result pdf = pdfInspectionService.inspect(temporary);
                return pdf.accepted()
                        ? Result.admissionAllowed(PDF_MEDIA_TYPE, "qpdf-pdfbox-v1")
                        : Result.rejected(pdf.reasonCode());
            }
            EpubInspectionService.Result epub = epubInspectionService.inspect(temporary);
            return epub.accepted()
                    ? Result.admissionAllowed(EPUB_MEDIA_TYPE, "epub-structural-v2")
                    : Result.rejected(epub.reasonCode());
        } catch (Exception exception) {
            return Result.rejected("INSPECTION_DEPENDENCY_FAILED");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    throw new ScratchCleanupException();
                }
            }
        }
    }

    private boolean copyWithinLimit(InputStream input, Path target) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > properties.maximumInputBytes()) {
                    return false;
                }
                output.write(buffer, 0, read);
            }
        }
        return total > 0;
    }

    private static String detect(Path publication) throws IOException {
        byte[] prefix;
        try (InputStream input = Files.newInputStream(publication)) {
            prefix = input.readNBytes(128);
        }
        if (startsWith(prefix, PDF_MAGIC, 0)) {
            return PDF_MEDIA_TYPE;
        }
        if (epubMagic(prefix)) {
            return EPUB_MEDIA_TYPE;
        }
        return null;
    }

    private static boolean epubMagic(byte[] bytes) {
        if (bytes.length < 30 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            return false;
        }
        int flags = unsignedShort(bytes, 6);
        int method = unsignedShort(bytes, 8);
        int nameLength = unsignedShort(bytes, 26);
        int extraLength = unsignedShort(bytes, 28);
        int nameStart = 30;
        int contentStart = nameStart + nameLength + extraLength;
        return (flags & 1) == 0
                && method == 0
                && nameLength == EPUB_ENTRY_NAME.length
                && startsWith(bytes, EPUB_ENTRY_NAME, nameStart)
                && startsWith(bytes, EPUB_MEDIA_TYPE_MAGIC, contentStart);
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    private static boolean startsWith(byte[] bytes, byte[] expected, int offset) {
        if (offset < 0 || offset + expected.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static Result scanFailure(MalwareScanner.Result scan) {
        return switch (scan) {
            case CLEAN -> null;
            case DETECTED -> Result.rejected("MALWARE_DETECTED");
            case FAILED -> Result.rejected("MALWARE_SCAN_FAILED");
            case TIMED_OUT -> Result.rejected("INSPECTION_TIMEOUT");
        };
    }

    static final class ScratchCleanupException extends RuntimeException {
        ScratchCleanupException() {
            super("Inspection scratch cleanup failed");
        }
    }
}
