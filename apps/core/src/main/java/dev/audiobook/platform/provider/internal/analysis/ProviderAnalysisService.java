package dev.audiobook.platform.provider.internal.analysis;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.internal.capability.ProviderCapabilityService;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ProviderAnalysisService {

    AnalysisOutcome analyze(AnalysisCommand command);

    record AnalysisCommand(
            String operationId,
            UUID generationRecipeId,
            String capabilityProfileVersion,
            CanonicalUnit unit,
            OutputSchema outputSchema) {
    }

    sealed interface CanonicalUnit permits CanonicalTextUnit, CanonicalPageImageUnit {

        ProviderCapabilityService.InputKind kind();

        long units(String inputUnit);
    }

    record CanonicalTextUnit(String text) implements CanonicalUnit {

        @Override
        public ProviderCapabilityService.InputKind kind() {
            return ProviderCapabilityService.InputKind.CANONICAL_TEXT;
        }

        @Override
        public long units(String inputUnit) {
            return switch (inputUnit) {
                case "UTF8_CHARACTER" -> text == null ? 0 : text.codePointCount(0, text.length());
                case "UTF8_BYTE" -> text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
                default -> throw new IllegalArgumentException("Unsupported canonical text meter");
            };
        }
    }

    record CanonicalPageImageUnit(byte[] bytes, String mimeType) implements CanonicalUnit {

        public CanonicalPageImageUnit {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public ProviderCapabilityService.InputKind kind() {
            return ProviderCapabilityService.InputKind.CANONICAL_PAGE_IMAGE;
        }

        @Override
        public long units(String inputUnit) {
            if (!"IMAGE_BYTE".equals(inputUnit)) {
                throw new IllegalArgumentException("Unsupported canonical page-image meter");
            }
            return bytes.length;
        }
    }

    record OutputSchema(
            String version,
            Map<String, ValueType> fields,
            Set<String> requiredFields,
            boolean allowAdditionalFields) {

        public OutputSchema {
            fields = Map.copyOf(fields);
            requiredFields = Set.copyOf(requiredFields);
        }
    }

    enum ValueType {
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN
    }

    record AnalysisOutcome(JsonNode result, ProviderEvidence evidence) {
    }

    record ProviderEvidence(
            String providerRequestId,
            String capabilityProfileVersion,
            String actualModel,
            String actualRegion,
            String priceMeter,
            ProviderUsage usage) {
    }
}
