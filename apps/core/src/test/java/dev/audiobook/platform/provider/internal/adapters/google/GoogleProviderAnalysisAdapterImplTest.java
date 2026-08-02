package dev.audiobook.platform.provider.internal.adapters.google;

import dev.audiobook.platform.provider.internal.governance.ProviderCapabilityService;
import dev.audiobook.platform.provider.internal.analysis.ProviderAnalysisAdapter;
import dev.audiobook.platform.provider.internal.analysis.ProviderAnalysisService;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleProviderAnalysisAdapterImplTest {

    @Test
    void requestTranslatesTheCanonicalSchemaWithoutProductMetadata() {
        ProviderAnalysisAdapter.AnalysisRequest request = new ProviderAnalysisAdapter.AnalysisRequest(
                UUID.randomUUID().toString(),
                capability(),
                new ProviderAnalysisService.CanonicalPageImageUnit(
                        new byte[] {1, 2, 3},
                        "image/png"),
                new ProviderAnalysisService.OutputSchema(
                        "page-analysis-v1",
                        Map.of("kind", ProviderAnalysisService.ValueType.STRING),
                        Set.of("kind"),
                        false));

        String body = GoogleProviderAnalysisAdapterImpl.body(request);

        assertThat(body)
                .contains("AQID")
                .contains("image/png")
                .contains("application/json")
                .contains("responseSchema")
                .doesNotContain("listener", "title", "filename", "objectUrl", "credential");
    }

    private static ProviderCapabilityService.CapabilityProfile capability() {
        return new ProviderCapabilityService.CapabilityProfile(
                UUID.randomUUID(),
                "google-analysis-image-eu-v1",
                "google",
                ProviderCapabilityService.ServiceKind.ANALYSIS,
                "https://europe-west1-aiplatform.googleapis.com/v1/projects/{project}/locations/europe-west1/publishers/google/models/{model}:generateContent",
                "gemini-2.5-flash-001",
                "SYNCHRONOUS",
                "europe-west1",
                "google-vertex-zdr-v1",
                Set.of(ProviderCapabilityService.InputKind.CANONICAL_PAGE_IMAGE),
                10_000_000,
                "IMAGE_BYTE",
                "TOKEN_PER_MINUTE",
                1_000_000,
                60,
                "INPUT_OUTPUT_TOKEN",
                "application/json",
                "application/json",
                "{\"responseMimeType\":\"application/json\"}",
                "{\"responseSchema\":{\"type\":\"OBJECT\"}}",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"));
    }
}
