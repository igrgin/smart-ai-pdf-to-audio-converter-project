package dev.audiobook.platform.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenAiProviderAnalysisAdapterImplTest {

    @Test
    void requestContainsOnlyTheCanonicalUnitOpaqueOperationAndNativeSchemaControls() {
        ProviderAnalysisAdapter.AnalysisRequest request = new ProviderAnalysisAdapter.AnalysisRequest(
                UUID.randomUUID().toString(),
                capability(),
                new ProviderAnalysisService.CanonicalTextUnit("Necessary paragraph only."),
                new ProviderAnalysisService.OutputSchema(
                        "classification-v1",
                        Map.of("classification", ProviderAnalysisService.ValueType.STRING),
                        Set.of("classification"),
                        false));

        String body = OpenAiProviderAnalysisAdapterImpl.body(request);

        assertThat(body)
                .contains("Necessary paragraph only.")
                .contains("classification-v1")
                .contains("\"store\":false")
                .doesNotContain("listener", "title", "filename", "objectUrl", "credential");
    }

    private static ProviderCapabilityService.CapabilityProfile capability() {
        return new ProviderCapabilityService.CapabilityProfile(
                UUID.randomUUID(),
                "openai-analysis-eu-v1",
                "openai",
                ProviderCapabilityService.ServiceKind.ANALYSIS,
                "https://eu.api.openai.com/v1/responses",
                "gpt-5-mini-2025-08-07",
                "SYNCHRONOUS",
                "eu",
                "openai-eu-zdr-v1",
                Set.of(ProviderCapabilityService.InputKind.CANONICAL_TEXT),
                250_000,
                "UTF8_CHARACTER",
                "REQUEST_PER_MINUTE",
                500,
                60,
                "INPUT_OUTPUT_TOKEN",
                "application/json",
                "application/json",
                "{\"store\":false,\"structured_output\":true}",
                "{\"output_schema\":{\"type\":\"json_schema\"}}",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"));
    }
}
