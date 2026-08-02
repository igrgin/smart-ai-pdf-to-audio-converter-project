package dev.audiobook.platform.provider.internal.analysis;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.internal.ProviderIntegrationProperties;
import dev.audiobook.platform.provider.internal.speech.GoogleProviderAccessTokenService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.provider.internal.ProviderRuntimeProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleProviderAnalysisAdapterImpl implements GoogleProviderAnalysisAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProviderIntegrationProperties providerProperties;
    private final ProviderRuntimeProperties generationProperties;
    private final GoogleProviderAccessTokenService accessTokenService;
    private final HttpClient httpClient;

    @Override
    public String provider() {
        return "google";
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        String endpoint = request.capability().endpoint()
                .replace("{project}", requiredProject())
                .replace("{model}", request.capability().modelSnapshot());
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(generationProperties.commandTimeout())
                .header("Authorization", "Bearer " + accessTokenService.accessToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Folio-Operation-Id", request.operationId())
                .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw rejected(ProviderAnalysisException.Code.RATE_LIMITED);
            }
            if (response.statusCode() >= 500) {
                throw rejected(ProviderAnalysisException.Code.PROVIDER_UNAVAILABLE);
            }
            if (response.statusCode() >= 400 || response.body() == null) {
                throw rejected(ProviderAnalysisException.Code.INVALID_RESPONSE);
            }
            return result(request, response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw rejected(ProviderAnalysisException.Code.PROVIDER_UNAVAILABLE);
        } catch (IOException exception) {
            throw rejected(ProviderAnalysisException.Code.PROVIDER_UNAVAILABLE);
        }
    }

    static String body(AnalysisRequest request) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", responseSchema(request.outputSchema()));
        Map<String, Object> root = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(part(request.unit())))),
                "generationConfig", generationConfig);
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw rejected(ProviderAnalysisException.Code.INVALID_COMMAND);
        }
    }

    private static Map<String, Object> part(ProviderAnalysisService.CanonicalUnit unit) {
        if (unit instanceof ProviderAnalysisService.CanonicalTextUnit text) {
            return Map.of("text", text.text());
        }
        if (unit instanceof ProviderAnalysisService.CanonicalPageImageUnit image) {
            return Map.of("inlineData", Map.of(
                    "mimeType", image.mimeType(),
                    "data", Base64.getEncoder().encodeToString(image.bytes())));
        }
        throw rejected(ProviderAnalysisException.Code.INVALID_COMMAND);
    }

    private static Map<String, Object> responseSchema(
            ProviderAnalysisService.OutputSchema schema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        schema.fields().forEach((name, type) -> properties.put(
                name,
                Map.of("type", googleType(type))));
        return Map.of(
                "type", "OBJECT",
                "properties", properties,
                "required", schema.requiredFields());
    }

    private static String googleType(ProviderAnalysisService.ValueType type) {
        return switch (type) {
            case STRING -> "STRING";
            case NUMBER -> "NUMBER";
            case INTEGER -> "INTEGER";
            case BOOLEAN -> "BOOLEAN";
        };
    }

    private static AnalysisResult result(AnalysisRequest request, String responseBody) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            String outputText = response.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text").asText(null);
            String actualModel = response.path("modelVersion").asText(null);
            long inputTokens = response.path("usageMetadata").path("promptTokenCount").asLong(-1);
            long outputTokens = response.path("usageMetadata").path("candidatesTokenCount").asLong(-1);
            if (outputText == null || actualModel == null || inputTokens < 0 || outputTokens < 0) {
                throw rejected(ProviderAnalysisException.Code.INVALID_RESPONSE);
            }
            return new AnalysisResult(
                    response.path("responseId").asText(null),
                    actualModel,
                    request.capability().region(),
                    OBJECT_MAPPER.readTree(outputText),
                    new ProviderUsage("INPUT_TOKEN", inputTokens, "OUTPUT_TOKEN", outputTokens));
        } catch (JsonProcessingException exception) {
            throw rejected(ProviderAnalysisException.Code.INVALID_RESPONSE);
        }
    }

    private String requiredProject() {
        String project = providerProperties.googleCloudProject();
        if (project == null || project.isBlank()) {
            throw rejected(ProviderAnalysisException.Code.CONFIGURATION_UNAVAILABLE);
        }
        return project;
    }

    private static ProviderAnalysisException rejected(ProviderAnalysisException.Code code) {
        return new ProviderAnalysisException(code);
    }
}
