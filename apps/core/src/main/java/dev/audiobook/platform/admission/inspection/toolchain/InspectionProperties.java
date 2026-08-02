package dev.audiobook.platform.admission.inspection.toolchain;

import dev.audiobook.platform.admission.inspection.toolchain.service.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("platform.admission.inspection")
public record InspectionProperties(
        long maximumInputBytes,
        int maximumPdfPages,
        int maximumEpubEntries,
        long maximumEpubExpandedBytes,
        int maximumCompressionRatio,
        long maximumXmlBytes,
        long maximumRenderedPixels,
        Duration commandTimeout,
        Duration runtime,
        int maximumAttempts,
        Path scratchPath,
        String malwareCommand,
        String qpdfCommand) {

    public InspectionProperties {
        if (maximumInputBytes <= 0
                || maximumPdfPages <= 0
                || maximumEpubEntries <= 0
                || maximumEpubExpandedBytes <= 0
                || maximumCompressionRatio <= 0
                || maximumXmlBytes <= 0
                || maximumRenderedPixels <= 0
                || maximumAttempts <= 0) {
            throw new IllegalArgumentException("Inspection limits must be positive");
        }
        if (commandTimeout == null
                || commandTimeout.isNegative()
                || commandTimeout.isZero()
                || runtime == null
                || runtime.isNegative()
                || runtime.isZero()) {
            throw new IllegalArgumentException("Inspection time limits must be positive");
        }
        if (scratchPath == null
                || malwareCommand == null
                || malwareCommand.isBlank()
                || qpdfCommand == null
                || qpdfCommand.isBlank()) {
            throw new IllegalArgumentException("Inspection runtime configuration is required");
        }
    }
}
