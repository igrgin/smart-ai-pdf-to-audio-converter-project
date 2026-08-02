package dev.audiobook.platform.provider.internal.adapters.openai;

import dev.audiobook.platform.provider.ProviderUsage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.provider.internal.adapters.ProviderRuntimeProperties;
import dev.audiobook.platform.provider.internal.analysis.ProviderAnalysisException;
import dev.audiobook.platform.provider.internal.analysis.ProviderAnalysisService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAiProviderAnalysisAdapterImpl implements OpenAiProviderAnalysisAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ProviderRuntimeProperties properties;
    private final HttpClient httpClient;

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest request) {
        if (properties.openAiApiKey() == null || properties.openAiApiKey().isBlank()) {
            throw rejected(ProviderAnalysisException.Code.CONFIGURATION_UNAVAILABLE);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.capability().endpoint()))
                .timeout(properties.commandTimeout())
                .header("Authorization", "Bearer " + properties.openAiApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Client-Request-Id", request.operationId())
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
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", request.capability().modelSnapshot());
        root.put("store", false);
        root.put("input", List.of(Map.of(
                "role", "user",
                "content", inputContent(request.unit()))));
        root.put("text", Map.of("format", Map.of(
                "type", "json_schema",
                "name", request.outputSchema().version(),
                "strict", true,
                "schema", jsonSchema(request.outputSchema()))));
        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw rejected(ProviderAnalysisException.Code.INVALID_COMMAND);
        }
    }

    private static List<Map<String, Object>> inputContent(
            ProviderAnalysisService.CanonicalUnit unit) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (unit instanceof ProviderAnalysisService.CanonicalTextUnit text) {
            content.add(Map.of("type", "input_text", "text", text.text()));
        } else if (unit instanceof ProviderAnalysisService.CanonicalPageImageUnit image) {
            String data = "data:" + image.mimeType() + ";base64,"
                    + Base64.getEncoder().encodeToString(image.bytes());
            content.add(Map.of("type", "input_image", "image_url", data));
        } else {
            throw rejected(ProviderAnalysisException.Code.INVALID_COMMAND);
        }
        return List.copyOf(content);
    }

    private static Map<String, Object> jsonSchema(
            ProviderAnalysisService.OutputSchema schema) {
        Map<String, Object> properties = new LinkedHashMap<>();
        schema.fields().forEach((name, type) -> properties.put(
                name,
                Map.of("type", type.name().toLowerCase(Locale.ROOT))));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", schema.requiredFields(),
                "additionalProperties", schema.allowAdditionalFields());
    }

    private static AnalysisResult result(AnalysisRequest request, String responseBody) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            String outputText = response.path("output").path(0).path("content").path(0).path("text").asText(null);
            String requestId = response.path("id").asText(null);
            String actualModel = response.path("model").asText(null);
            long inputTokens = response.path("usage").path("input_tokens").asLong(-1);
            long outputTokens = response.path("usage").path("output_tokens").asLong(-1);
            if (outputText == null || actualModel == null || inputTokens < 0 || outputTokens < 0) {
                throw rejected(ProviderAnalysisException.Code.INVALID_RESPONSE);
            }
            return new AnalysisResult(
                    requestId,
                    actualModel,
                    request.capability().region(),
                    OBJECT_MAPPER.readTree(outputText),
                    new ProviderUsage("INPUT_TOKEN", inputTokens, "OUTPUT_TOKEN", outputTokens));
        } catch (JsonProcessingException exception) {
            throw rejected(ProviderAnalysisException.Code.INVALID_RESPONSE);
        }
    }

    private static ProviderAnalysisException rejected(ProviderAnalysisException.Code code) {
        return new ProviderAnalysisException(code);
    }
}
