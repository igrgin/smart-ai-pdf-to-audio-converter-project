package dev.audiobook.pdfbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfBoxBoundaryMain {

    private static final float PREFERRED_RENDER_DPI = 300.0f;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PdfBoxBoundaryMain() {
    }

    public static void main(String[] arguments) {
        try {
            if (arguments.length < 2) {
                throw new IllegalArgumentException("operation and source are required");
            }
            Path source = Path.of(arguments[1]).toAbsolutePath();
            switch (arguments[0]) {
                case "inspect" -> inspect(source);
                case "text" -> text(source, integer(arguments, 2), integer(arguments, 3));
                case "render" -> render(
                        source,
                        integer(arguments, 2),
                        integer(arguments, 3),
                        Path.of(arguments[4]).toAbsolutePath());
                default -> throw new IllegalArgumentException("unsupported operation");
            }
        } catch (Exception exception) {
            System.err.println("pdfbox_boundary_failed");
            System.exit(2);
        }
    }

    private static void inspect(Path source) throws Exception {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            List<OutlineEvidence> outline = new ArrayList<>();
            PDDocumentOutline documentOutline = document.getDocumentCatalog().getDocumentOutline();
            if (documentOutline != null) {
                collectOutline(document, documentOutline.getFirstChild(), outline);
            }
            OBJECT_MAPPER.writeValue(System.out, new Inspection(document.getNumberOfPages(), outline));
        }
    }

    private static void collectOutline(
            PDDocument document, PDOutlineItem first, List<OutlineEvidence> evidence) throws Exception {
        PDOutlineItem current = first;
        while (current != null) {
            PDPage page = current.findDestinationPage(document);
            String title = current.getTitle() == null ? "" : current.getTitle().strip();
            if (page != null && !title.isBlank()) {
                int pageNumber = document.getPages().indexOf(page) + 1;
                if (pageNumber > 0) {
                    evidence.add(new OutlineEvidence(title, pageNumber, "outline:" + evidence.size()));
                }
            }
            collectOutline(document, current.getFirstChild(), evidence);
            current = current.getNextSibling();
        }
    }

    private static void text(Path source, int firstPage, int lastPage) throws Exception {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (firstPage < 1 || lastPage < firstPage || lastPage > document.getNumberOfPages()) {
                throw new IllegalArgumentException("invalid page range");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            Map<Integer, String> pages = new LinkedHashMap<>();
            for (int pageNumber = firstPage; pageNumber <= lastPage; pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                pages.put(pageNumber, normalize(stripper.getText(document)));
            }
            OBJECT_MAPPER.writeValue(System.out, pages);
        }
    }

    private static void render(Path source, int pageNumber, int maximumPixels, Path output) throws Exception {
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages() || maximumPixels < 1) {
                throw new IllegalArgumentException("invalid render request");
            }
            PDPage page = document.getPage(pageNumber - 1);
            double pointPixels = (double) page.getMediaBox().getWidth() * page.getMediaBox().getHeight();
            float boundedDpi = (float) Math.min(
                    PREFERRED_RENDER_DPI, 72.0 * Math.sqrt(maximumPixels / pointPixels));
            if (!Float.isFinite(boundedDpi) || boundedDpi <= 0.0f) {
                throw new IllegalArgumentException("invalid bounded dpi");
            }
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(
                    pageNumber - 1, boundedDpi, ImageType.RGB);
            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels > maximumPixels || !ImageIO.write(image, "PNG", output.toFile())) {
                throw new IllegalStateException("render limit exceeded");
            }
            OBJECT_MAPPER.writeValue(System.out, new RenderedImage(image.getWidth(), image.getHeight()));
        }
    }

    private static int integer(String[] arguments, int index) {
        if (index >= arguments.length) {
            throw new IllegalArgumentException("missing integer argument");
        }
        return Integer.parseInt(arguments[index]);
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private record Inspection(int pageCount, List<OutlineEvidence> outline) {
    }

    private record OutlineEvidence(String title, int pageNumber, String anchor) {
    }

    private record RenderedImage(int width, int height) {
    }
}
