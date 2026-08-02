package dev.audiobook.platform.narration;

import dev.audiobook.platform.narration.internal.document.PdfBoxDoclingTesseractBoundaryImpl;
import dev.audiobook.platform.narration.internal.document.PdfNarrationPlanInterpreter;
import dev.audiobook.platform.narration.internal.document.PdfNarrationPlanInterpreterImpl;
import dev.audiobook.platform.narration.internal.document.PdfNarrationProperties;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

abstract class PdfExtractionCorpusContract {

    @TempDir
    protected Path scratch;

    protected abstract Toolchain toolchain(Corpus corpus) throws Exception;

    @Test
    void representativeRealPdfCorpusMeetsFidelityStructureAndProvenanceThresholds() throws Exception {
        List<String> expectedPages = List.of(
                "A precise born-digital opening.",
                "The second page preserves punctuation, spacing, and order.",
                "A scanned page remains accurately recoverable.",
                "A second chapter begins with source-backed evidence.",
                "The damaged scan has one smudged character.");
        Path publication = representativePdf(expectedPages);

        PublicationNarrationPlanInterpreter.NarrationPlan plan;
        try (Toolchain toolchain = toolchain(Corpus.MIXED_OUTLINE);
                InputStream source = Files.newInputStream(publication)) {
            var properties = new PdfNarrationProperties(
                    2,
                    2,
                    1,
                    4_000_000,
                    toolchain.timeout(),
                    scratch,
                    "java",
                    System.getProperty("java.class.path"),
                    "dev.audiobook.pdfbox.PdfBoxBoundaryMain",
                    256,
                    toolchain.doclingCommand().toString(),
                    "ignored-docling-adapter",
                    toolchain.tesseractCommand().toString());
            PdfNarrationPlanInterpreter interpreter = new PdfNarrationPlanInterpreterImpl(
                    new PdfBoxDoclingTesseractBoundaryImpl(properties), properties);
            plan = interpreter.interpret(source);
        }

        List<PublicationNarrationPlanInterpreter.NormalProse> prose = plan.chapters().stream()
                .flatMap(chapter -> chapter.normalProse().stream())
                .sorted(Comparator.comparingInt(PublicationNarrationPlanInterpreter.NormalProse::sourceOrdinal))
                .toList();
        List<String> actualPages = prose.stream()
                .map(PublicationNarrationPlanInterpreter.NormalProse::text)
                .toList();
        double bornDigitalFidelity = 1.0 - characterErrorRate(
                String.join("\n", expectedPages.subList(0, 2)),
                String.join("\n", actualPages.subList(0, 2)));
        List<Double> ocrErrorDistribution = List.of(
                characterErrorRate(expectedPages.get(2), actualPages.get(2)),
                characterErrorRate(expectedPages.get(4), actualPages.get(4)));
        double chapterInclusionAndOrder = plan.chapters().stream()
                        .map(PublicationNarrationPlanInterpreter.Chapter::title)
                        .toList()
                        .equals(List.of("Opening", "Evidence"))
                ? 1.0
                : 0.0;
        double chapterBoundaryF1 = boundaryF1(
                List.of(1, 4),
                plan.chapters().stream().map(chapter -> chapter.provenance().sourceIndex() + 1).toList());

        assertThat(bornDigitalFidelity).isGreaterThanOrEqualTo(0.995);
        assertThat(median(ocrErrorDistribution)).isLessThan(0.02);
        assertThat(percentile95(ocrErrorDistribution)).isLessThan(0.08);
        assertThat(chapterInclusionAndOrder).isGreaterThanOrEqualTo(0.99);
        assertThat(chapterBoundaryF1).isGreaterThanOrEqualTo(0.95);
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().sourceIndex())
                .containsExactly(0, 3);
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().anchor())
                .containsExactly("outline:0", "outline:1");
        assertThat(prose)
                .extracting(unit -> unit.provenance().sourceUnit())
                .containsExactly("page:1", "page:2", "page:3", "page:4", "page:5");
        assertThat(prose).allSatisfy(unit -> {
            assertThat(unit.provenance().sourceUnit()).startsWith("page:");
            assertThat(unit.provenance().source())
                    .isIn(
                            PublicationNarrationPlanInterpreter.ProvenanceSource.PDFBOX_TEXT,
                            PublicationNarrationPlanInterpreter.ProvenanceSource.TESSERACT_OCR,
                            PublicationNarrationPlanInterpreter.ProvenanceSource.PDF_LAYOUT);
        });
    }

    @Test
    void imageOnlyNoOutlineAndPartiallyUnreadableCorpusRetainsDetectedHierarchyAndExactGaps()
            throws Exception {
        Path publication = imageOnlyDamagedPdf();

        PublicationNarrationPlanInterpreter.NarrationPlan plan;
        try (Toolchain toolchain = toolchain(Corpus.IMAGE_ONLY_DAMAGED);
                InputStream source = Files.newInputStream(publication)) {
            var properties = new PdfNarrationProperties(
                    2,
                    2,
                    1,
                    4_000_000,
                    toolchain.timeout(),
                    scratch,
                    "java",
                    System.getProperty("java.class.path"),
                    "dev.audiobook.pdfbox.PdfBoxBoundaryMain",
                    256,
                    toolchain.doclingCommand().toString(),
                    "ignored-docling-adapter",
                    toolchain.tesseractCommand().toString());
            plan = new PdfNarrationPlanInterpreterImpl(
                            new PdfBoxDoclingTesseractBoundaryImpl(properties), properties)
                    .interpret(source);
        }

        assertThat(plan.chapters())
                .extracting(PublicationNarrationPlanInterpreter.Chapter::title)
                .containsExactly("CHAPTER ONE", "CHAPTER TWO");
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().source())
                .containsOnly(PublicationNarrationPlanInterpreter.ProvenanceSource.DOCLING_HIERARCHY);
        assertThat(plan.chapters())
                .extracting(chapter -> chapter.provenance().sourceIndex())
                .containsExactly(0, 2);
        assertThat(plan.chapters().getFirst().gaps())
                .containsExactly(new PublicationNarrationPlanInterpreter.Gap(
                        "page:2", "UNREADABLE_PDF_PAGE"));
        assertThat(plan.reviewItems())
                .extracting(item -> item.provenance().sourceUnit())
                .containsExactly("page:2");
        assertThat(plan.chapters().stream()
                        .flatMap(chapter -> chapter.normalProse().stream())
                        .map(unit -> unit.provenance().sourceUnit()))
                .containsExactly("page:1", "page:3", "page:4");
        Map<String, String> recoveredByPage = plan.chapters().stream()
                .flatMap(chapter -> chapter.normalProse().stream())
                .collect(java.util.stream.Collectors.toMap(
                        unit -> unit.provenance().sourceUnit(),
                        PublicationNarrationPlanInterpreter.NormalProse::text));
        assertThat(recoveredByPage.get("page:1")).contains("Fully scanned opening");
        assertThat(recoveredByPage.get("page:4")).contains("Scanned continuation");
        assertThat(characterErrorRate(
                        "Degraded but recoverable text.", recoveredByPage.get("page:3")))
                .isLessThan(0.15);
    }

    protected Path executable(String name, String body) throws Exception {
        Path command = scratch.resolve(name);
        Files.writeString(command, body, StandardCharsets.UTF_8);
        assertThat(command.toFile().setExecutable(true)).isTrue();
        return command;
    }

    private Path representativePdf(List<String> pages) throws Exception {
        Path publication = scratch.resolve("representative-corpus-v1.pdf");
        try (PDDocument document = new PDDocument()) {
            for (int index = 0; index < pages.size(); index++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (index == 2 || index == 4) {
                        BufferedImage scan = scan(pages.get(index));
                        content.drawImage(LosslessFactory.createFromImage(document, scan), 36, 600, 540, 90);
                    } else {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(72, 720);
                        content.showText(pages.get(index));
                        content.endText();
                    }
                }
            }
            addOutline(document, "Opening", 0);
            addOutline(document, "Evidence", 3);
            document.save(publication.toFile());
        }
        return publication;
    }

    private Path imageOnlyDamagedPdf() throws Exception {
        Path publication = scratch.resolve("image-only-damaged-corpus-v1.pdf");
        try (PDDocument document = new PDDocument()) {
            addScanPage(document, "CHAPTER ONE", "Fully scanned opening.", false);
            document.addPage(new PDPage());
            addScanPage(document, "CHAPTER TWO", "Degraded but recoverable text.", true);
            addScanPage(document, null, "Scanned continuation.", false);
            document.save(publication.toFile());
        }
        return publication;
    }

    private static void addScanPage(PDDocument document, String heading, String prose, boolean degraded)
            throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        BufferedImage scan = new BufferedImage(1_200, 1_600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scan.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
            graphics.setColor(Color.BLACK);
            if (heading != null) {
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
                graphics.drawString(heading, 70, 190);
            }
            graphics.setFont(new Font(Font.SERIF, Font.PLAIN, 46));
            graphics.drawString(prose, 70, heading == null ? 190 : 360);
            if (degraded) {
                graphics.setColor(new Color(230, 230, 230));
                for (int x = 85; x < 1_050; x += 37) {
                    graphics.drawLine(x, 305, x + 18, 390);
                }
                graphics.setColor(Color.WHITE);
                graphics.fillRect(560, 325, 22, 48);
            }
        } finally {
            graphics.dispose();
        }
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.drawImage(LosslessFactory.createFromImage(document, scan), 36, 60, 540, 700);
        }
    }

    private static BufferedImage scan(String text) {
        BufferedImage image = new BufferedImage(1_800, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 42));
            graphics.drawString(text, 30, 170);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void addOutline(PDDocument document, String title, int pageIndex) {
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline == null) {
            outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
        }
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(document.getPage(pageIndex));
        item.setDestination(destination);
        outline.addLast(item);
    }

    private static double characterErrorRate(String expected, String actual) {
        int[][] distance = new int[expected.length() + 1][actual.length() + 1];
        for (int left = 0; left <= expected.length(); left++) {
            distance[left][0] = left;
        }
        for (int right = 0; right <= actual.length(); right++) {
            distance[0][right] = right;
        }
        for (int left = 1; left <= expected.length(); left++) {
            for (int right = 1; right <= actual.length(); right++) {
                int substitution = expected.charAt(left - 1) == actual.charAt(right - 1) ? 0 : 1;
                distance[left][right] = Math.min(
                        Math.min(distance[left - 1][right] + 1, distance[left][right - 1] + 1),
                        distance[left - 1][right - 1] + substitution);
            }
        }
        return (double) distance[expected.length()][actual.length()] / expected.length();
    }

    private static double boundaryF1(List<Integer> expected, List<Integer> actual) {
        long matches = actual.stream().filter(expected::contains).count();
        double precision = (double) matches / actual.size();
        double recall = (double) matches / expected.size();
        return 2.0 * precision * recall / (precision + recall);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return (sorted.getFirst() + sorted.getLast()) / 2.0;
    }

    private static double percentile95(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    protected record Toolchain(
            Path doclingCommand,
            Path tesseractCommand,
            Duration timeout,
            AutoCloseable resource) implements AutoCloseable {

        protected Toolchain(Path doclingCommand, Path tesseractCommand, Duration timeout) {
            this(doclingCommand, tesseractCommand, timeout, () -> { });
        }

        @Override
        public void close() throws Exception {
            resource.close();
        }
    }

    protected enum Corpus {
        MIXED_OUTLINE,
        IMAGE_ONLY_DAMAGED
    }
}
