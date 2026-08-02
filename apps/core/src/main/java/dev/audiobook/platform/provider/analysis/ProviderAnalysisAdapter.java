package dev.audiobook.platform.provider.analysis;

import com.fasterxml.jackson.databind.JsonNode;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.analysis.service.*;
import dev.audiobook.platform.provider.governance.service.ProviderCapabilityService;

public interface ProviderAnalysisAdapter {

    String provider();

    AnalysisResult analyze(AnalysisRequest request);

    record AnalysisRequest(
            String operationId,
            ProviderCapabilityService.CapabilityProfile capability,
            ProviderAnalysisService.CanonicalUnit unit,
            ProviderAnalysisService.OutputSchema outputSchema) {
        public AnalysisRequest {
            java.util.UUID.fromString(operationId);
            java.util.Objects.requireNonNull(capability, "capability");
            java.util.Objects.requireNonNull(unit, "unit");
            java.util.Objects.requireNonNull(outputSchema, "outputSchema");
        }
    }

    record AnalysisResult(
            String providerRequestId,
            String actualModel,
            String actualRegion,
            JsonNode result,
            ProviderUsage usage) {}
}
