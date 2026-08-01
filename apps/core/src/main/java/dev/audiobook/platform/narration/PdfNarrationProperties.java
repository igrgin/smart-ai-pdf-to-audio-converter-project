package dev.audiobook.platform.narration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.narration.pdf")
public record PdfNarrationProperties(
        int pageBatchSize,
        int maximumUnreadablePages,
        int maximumConsecutiveUnreadablePages,
        int maximumRenderedPixels,
        Duration commandTimeout,
        Path scratchPath,
        String pdfBoxJavaCommand,
        String pdfBoxClasspath,
        String pdfBoxMainClass,
        int maximumPdfBoxHeapMegabytes,
        String pythonCommand,
        String doclingScript,
        String tesseractCommand) {

    public PdfNarrationProperties {
        if (pageBatchSize < 1
                || maximumUnreadablePages < 0
                || maximumConsecutiveUnreadablePages < 0
                || maximumRenderedPixels < 1
                || commandTimeout == null
                || commandTimeout.isNegative()
                || commandTimeout.isZero()
                || scratchPath == null
                || pdfBoxJavaCommand == null
                || pdfBoxJavaCommand.isBlank()
                || pdfBoxClasspath == null
                || pdfBoxClasspath.isBlank()
                || pdfBoxMainClass == null
                || pdfBoxMainClass.isBlank()
                || maximumPdfBoxHeapMegabytes < 64
                || pythonCommand == null
                || pythonCommand.isBlank()
                || doclingScript == null
                || doclingScript.isBlank()
                || tesseractCommand == null
                || tesseractCommand.isBlank()) {
            throw new IllegalArgumentException("PDF narration recovery properties must be bounded");
        }
    }
}
