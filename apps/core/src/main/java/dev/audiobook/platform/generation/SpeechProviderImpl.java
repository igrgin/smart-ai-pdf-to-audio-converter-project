package dev.audiobook.platform.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SpeechProviderImpl implements SpeechProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AudioGenerationProperties properties;
    private final HttpClient httpClient;

    public SpeechProviderImpl(AudioGenerationProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.commandTimeout())
                .build();
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        validate(request);
        if (properties.openAiApiKey() == null || properties.openAiApiKey().isBlank()) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.CONFIGURATION_UNAVAILABLE, false);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(request.endpoint())
                .timeout(properties.commandTimeout())
                .header("Authorization", "Bearer " + properties.openAiApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 429) {
                throw new SpeechProviderException(SpeechProviderException.Code.RATE_LIMITED, true);
            }
            if (response.statusCode() >= 500) {
                throw new SpeechProviderException(
                        SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true);
            }
            if (response.statusCode() >= 400) {
                throw new SpeechProviderException(SpeechProviderException.Code.INVALID_REQUEST, false);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new SpeechProviderException(SpeechProviderException.Code.INVALID_RESPONSE, false);
            }
            String requestId = response.headers()
                    .firstValue("x-request-id")
                    .orElse(null);
            return new SpeechResult(
                    requestId, request.model(), request.region(), request.voice(), response.body());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.AMBIGUOUS_TIMEOUT, true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpeechProviderException(
                    SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true, exception);
        } catch (IOException exception) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true, exception);
        }
    }

    private static String body(SpeechRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("voice", request.voice());
        body.put("input", request.spokenText());
        body.put("instructions", request.instructions());
        body.put("speed", request.speed());
        body.put("response_format", "pcm");
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.INVALID_REQUEST, false, exception);
        }
    }

    private static void validate(SpeechRequest request) {
        if (request == null
                || request.endpoint() == null
                || !"https".equalsIgnoreCase(request.endpoint().getScheme())
                || blank(request.model())
                || blank(request.region())
                || blank(request.voice())
                || blank(request.instructions())
                || blank(request.spokenText())
                || !Double.isFinite(request.speed())
                || request.speed() <= 0) {
            throw new SpeechProviderException(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
