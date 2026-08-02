package dev.audiobook.platform.narration.internal.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PdfBoxDoclingTesseractBoundaryImpl implements PdfDocumentUnderstandingBoundary {

    private static final int MAXIMUM_COMMAND_OUTPUT_BYTES = 32 * 1024 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PdfNarrationProperties properties;

    @Override
    public DocumentProfile inspect(Path publication) {
        JsonNode root = json(runPdfBox(List.of("inspect", publication.toString())), "PDFBox inspection");
        if (!root.isObject() || !root.path("pageCount").canConvertToInt() || !root.path("outline").isArray()) {
            throw new DocumentUnderstandingException("PDFBox returned invalid inspection evidence");
        }
        List<OutlineEntry> outline = new ArrayList<>();
        for (JsonNode item : root.path("outline")) {
            String title = item.path("title").textValue();
            String anchor = item.path("anchor").textValue();
            if (title == null || anchor == null || !item.path("pageNumber").canConvertToInt()) {
                throw new DocumentUnderstandingException("PDFBox returned invalid outline evidence");
            }
            outline.add(new OutlineEntry(title, item.path("pageNumber").intValue(), anchor));
        }
        return new DocumentProfile(root.path("pageCount").intValue(), outline);
    }

    @Override
    public List<PageEvidence> understandBatch(Path publication, PageRange range) {
        Map<Integer, String> nativeText = nativeText(publication, range);
        Map<Integer, List<LayoutItem>> layout = docling(publication, range);
        List<PageEvidence> evidence = merge(range, nativeText, layout);
        if (evidence.stream().anyMatch(page -> !page.readable())) {
            throw new RecoverableDocumentUnderstandingException("A PDF page requires bounded per-page recovery");
        }
        return evidence;
    }

    @Override
    public PageEvidence understandPage(Path publication, int pageNumber) {
        PageRange range = new PageRange(pageNumber, pageNumber);
        Map<Integer, String> nativeText;
        try {
            nativeText = nativeText(publication, range);
        } catch (RecoverableDocumentUnderstandingException exception) {
            throw exception;
        } catch (DocumentUnderstandingException exception) {
            nativeText = Map.of();
        }
        try {
            PageEvidence evidence = merge(range, nativeText, docling(publication, range)).getFirst();
            if (evidence.readable()) {
                return evidence;
            }
        } catch (RecoverableDocumentUnderstandingException exception) {
            String text = nativeText.getOrDefault(pageNumber, "");
            if (!text.isBlank()) {
                return new PageEvidence(pageNumber, text, TextSource.PDFBOX_TEXT, List.of());
            }
        }
        try {
            return renderAndOcr(publication, pageNumber);
        } catch (RecoverableDocumentUnderstandingException exception) {
            throw exception;
        } catch (DocumentUnderstandingException exception) {
            return unreadable(pageNumber);
        }
    }

    private Map<Integer, String> nativeText(Path publication, PageRange range) {
        byte[] output;
        try {
            output = runPdfBox(List.of(
                    "text",
                    publication.toString(),
                    Integer.toString(range.firstPage()),
                    Integer.toString(range.lastPage())));
        } catch (DocumentUnderstandingCommandRejectedException exception) {
            if (range.pageCount() > 1) {
                throw new RecoverableDocumentUnderstandingException(
                        "PDFBox rejected a bounded page batch", exception);
            }
            throw exception;
        }
        JsonNode root = json(output, "PDFBox text extraction");
        if (!root.isObject()) {
            throw new DocumentUnderstandingException("PDFBox returned invalid text evidence");
        }
        Map<Integer, String> pages = new LinkedHashMap<>();
        root.fields().forEachRemaining(field -> {
            int pageNumber;
            try {
                pageNumber = Integer.parseInt(field.getKey());
            } catch (NumberFormatException exception) {
                throw new DocumentUnderstandingException("PDFBox returned invalid text evidence", exception);
            }
            if (pageNumber < range.firstPage()
                    || pageNumber > range.lastPage()
                    || !field.getValue().isTextual()
                    || pages.putIfAbsent(pageNumber, normalize(field.getValue().textValue())) != null) {
                throw new DocumentUnderstandingException("PDFBox returned invalid text evidence");
            }
        });
        if (pages.size() != range.pageCount()) {
            throw new DocumentUnderstandingException("PDFBox returned incomplete text evidence");
        }
        return pages;
    }

    private Map<Integer, List<LayoutItem>> docling(Path publication, PageRange range) {
        byte[] output;
        try {
            output = run(List.of(
                    properties.pythonCommand(),
                    properties.doclingScript(),
                    publication.toString(),
                    Integer.toString(range.firstPage()),
                    Integer.toString(range.lastPage())));
        } catch (DocumentUnderstandingCommandRejectedException exception) {
            throw new RecoverableDocumentUnderstandingException(
                    "Docling rejected a bounded page batch", exception);
        }
        JsonNode root = json(output, "Docling");
        if (!root.isArray()) {
            throw new DocumentUnderstandingException("Docling returned invalid document evidence");
        }
        Map<Integer, List<LayoutItem>> pages = new LinkedHashMap<>();
        for (JsonNode page : root) {
            if (!page.isObject() || !page.path("pageNumber").canConvertToInt() || !page.path("items").isArray()) {
                throw new DocumentUnderstandingException("Docling returned invalid document evidence");
            }
            int pageNumber = page.path("pageNumber").intValue();
            if (pageNumber < range.firstPage() || pageNumber > range.lastPage()) {
                throw new DocumentUnderstandingException("Docling returned evidence outside the requested page range");
            }
            List<LayoutItem> items = new ArrayList<>();
            for (JsonNode item : page.path("items")) {
                items.add(layoutItem(item));
            }
            if (pages.putIfAbsent(pageNumber, List.copyOf(items)) != null) {
                throw new DocumentUnderstandingException("Docling returned duplicate page evidence");
            }
        }
        return pages;
    }

    private static JsonNode json(byte[] output, String tool) {
        try {
            JsonNode value = OBJECT_MAPPER.readTree(output);
            if (value == null) {
                throw new DocumentUnderstandingException(tool + " returned invalid document evidence");
            }
            return value;
        } catch (IOException exception) {
            throw new DocumentUnderstandingException(tool + " returned invalid document evidence", exception);
        }
    }

    private static LayoutItem layoutItem(JsonNode item) {
        String role = item.path("role").textValue();
        String text = item.path("text").textValue();
        JsonNode confidence = item.path("confidence");
        if (role == null || text == null || !confidence.isNumber()) {
            throw new DocumentUnderstandingException("Docling returned invalid document evidence");
        }
        try {
            return new LayoutItem(
                    LayoutRole.valueOf(role.toUpperCase(Locale.ROOT)), text, confidence.doubleValue());
        } catch (IllegalArgumentException exception) {
            throw new DocumentUnderstandingException("Docling returned invalid layout evidence", exception);
        }
    }

    private static List<PageEvidence> merge(
            PageRange range,
            Map<Integer, String> nativeText,
            Map<Integer, List<LayoutItem>> layout) {
        List<PageEvidence> evidence = new ArrayList<>(range.pageCount());
        for (int pageNumber = range.firstPage(); pageNumber <= range.lastPage(); pageNumber++) {
            String exactText = nativeText.getOrDefault(pageNumber, "");
            List<LayoutItem> items = layout.getOrDefault(pageNumber, List.of());
            if (!exactText.isBlank()) {
                evidence.add(new PageEvidence(pageNumber, exactText, TextSource.PDFBOX_TEXT, items));
                continue;
            }
            String doclingText = items.stream()
                    .filter(item -> item.role() == LayoutRole.NORMAL_PROSE)
                    .map(LayoutItem::text)
                    .filter(text -> text != null && !text.isBlank())
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
            evidence.add(new PageEvidence(
                    pageNumber,
                    doclingText,
                    doclingText.isBlank() ? TextSource.UNREADABLE : TextSource.DOCLING_LAYOUT,
                    items));
        }
        return evidence;
    }

    private PageEvidence renderAndOcr(Path publication, int pageNumber) {
        Path image = null;
        try {
            Files.createDirectories(properties.scratchPath());
            image = Files.createTempFile(properties.scratchPath(), "page-", ".png");
            JsonNode rendered = json(runPdfBox(List.of(
                    "render",
                    publication.toString(),
                    Integer.toString(pageNumber),
                    Integer.toString(properties.maximumRenderedPixels()),
                    image.toString())), "PDFBox rendering");
            if (!rendered.path("width").canConvertToInt() || !rendered.path("height").canConvertToInt()) {
                throw new DocumentUnderstandingException("PDFBox returned invalid rendering evidence");
            }
            long pixels = (long) rendered.path("width").intValue() * rendered.path("height").intValue();
            if (pixels < 1 || pixels > properties.maximumRenderedPixels() || Files.size(image) == 0) {
                throw new DocumentUnderstandingException("PDF page rendering exceeded the pixel limit");
            }
            String text = normalize(new String(run(List.of(
                    properties.tesseractCommand(),
                    image.toString(),
                    "stdout",
                    "-l",
                    "eng",
                    "--psm",
                    "3")), StandardCharsets.UTF_8));
            return new PageEvidence(
                    pageNumber,
                    text,
                    text.isBlank() ? TextSource.UNREADABLE : TextSource.TESSERACT_OCR,
                    List.of());
        } catch (IOException exception) {
            throw new DocumentUnderstandingException("Bounded PDF page OCR is unavailable", exception);
        } finally {
            delete(image);
        }
    }

    private byte[] runPdfBox(List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(properties.pdfBoxJavaCommand());
        command.add("-Xmx" + properties.maximumPdfBoxHeapMegabytes() + "m");
        command.add("-cp");
        command.add(properties.pdfBoxClasspath());
        command.add(properties.pdfBoxMainClass());
        command.addAll(arguments);
        return run(command);
    }

    private byte[] run(List<String> command) {
        Path output = null;
        try {
            Files.createDirectories(properties.scratchPath());
            output = Files.createTempFile(properties.scratchPath(), "document-understanding-", ".out");
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean completed = process.waitFor(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                throw new RecoverableDocumentUnderstandingException("Document understanding command timed out");
            }
            if (process.exitValue() != 0) {
                throw new DocumentUnderstandingCommandRejectedException(
                        "Document understanding command rejected its source unit");
            }
            if (Files.size(output) > MAXIMUM_COMMAND_OUTPUT_BYTES) {
                throw new DocumentUnderstandingException("Document understanding output exceeded the byte limit");
            }
            return Files.readAllBytes(output);
        } catch (IOException exception) {
            throw new RecoverableDocumentUnderstandingException(
                    "Document understanding command is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RecoverableDocumentUnderstandingException(
                    "Document understanding command was interrupted", exception);
        } finally {
            delete(output);
        }
    }

    private static void delete(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Reconciliation removes abandoned opaque worker scratch files.
            }
        }
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static PageEvidence unreadable(int pageNumber) {
        return new PageEvidence(pageNumber, "", TextSource.UNREADABLE, List.of());
    }

    private static final class DocumentUnderstandingCommandRejectedException
            extends DocumentUnderstandingException {

        private DocumentUnderstandingCommandRejectedException(String message) {
            super(message);
        }
    }
}
