package dev.audiobook.platform.provider.speech;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.provider.SpeechProvider;
import dev.audiobook.platform.provider.SpeechProviderException;
import dev.audiobook.platform.provider.adapters.ProviderRuntimeProperties;
import dev.audiobook.platform.provider.speech.service.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SpeechProviderImpl implements SpeechProvider {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final java.net.URI APPROVED_SPEECH_ENDPOINT =
            java.net.URI.create("https://eu.api.openai.com/v1/audio/speech");

    private final ProviderRuntimeProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public SpeechProviderImpl(ProviderRuntimeProperties properties) {
        this(
                properties,
                HttpClient.newBuilder().connectTimeout(properties.commandTimeout()).build());
    }

    SpeechProviderImpl(ProviderRuntimeProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        validate(request);
        if (properties.openAiApiKey() == null || properties.openAiApiKey().isBlank()) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.CONFIGURATION_UNAVAILABLE, false);
        }
        HttpRequest httpRequest =
                HttpRequest.newBuilder(request.endpoint())
                        .timeout(properties.commandTimeout())
                        .header("Authorization", "Bearer " + properties.openAiApiKey())
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/octet-stream")
                        .header("X-Client-Request-Id", request.operationId())
                        .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                        .build();
        try {
            HttpResponse<byte[]> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 429) {
                throw new SpeechProviderException(SpeechProviderException.Code.RATE_LIMITED, true);
            }
            if (response.statusCode() >= 500) {
                throw new SpeechProviderException(
                        SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true);
            }
            if (response.statusCode() >= 400) {
                throw new SpeechProviderException(
                        SpeechProviderException.Code.INVALID_REQUEST, false);
            }
            if (response.body() == null || response.body().length == 0) {
                throw new SpeechProviderException(
                        SpeechProviderException.Code.INVALID_RESPONSE, false);
            }
            String requestId = response.headers().firstValue("x-request-id").orElse(null);
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

    static String body(SpeechRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("voice", request.voice());
        body.put("input", request.spokenText());
        body.put("instructions", request.instructions());
        body.put("speed", request.speed());
        body.put("response_format", "wav");
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new SpeechProviderException(
                    SpeechProviderException.Code.INVALID_REQUEST, false, exception);
        }
    }

    private static void validate(SpeechRequest request) {
        if (request == null
                || blank(request.operationId())
                || request.operationId().length() > 100
                || !opaque(request.operationId())
                || request.endpoint() == null
                || !APPROVED_SPEECH_ENDPOINT.equals(request.endpoint())
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

    private static boolean opaque(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
