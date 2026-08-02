package dev.audiobook.platform.provider.internal.speech;

import dev.audiobook.platform.provider.ProviderUsage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.provider.internal.ProviderRuntimeProperties;
import dev.audiobook.platform.provider.SpeechProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleProviderSpeechAdapterImpl implements GoogleProviderSpeechAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private final ProviderRuntimeProperties properties;
    private final GoogleProviderAccessTokenService accessTokenService;
    private final HttpClient httpClient;

    @Override
    public String provider() {
        return "google";
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.capability().endpoint()))
                .timeout(properties.commandTimeout())
                .header("Authorization", "Bearer " + accessTokenService.accessToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Folio-Operation-Id", request.operationId())
                .POST(HttpRequest.BodyPublishers.ofString(body(request)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw failed(SpeechProviderException.Code.RATE_LIMITED, true);
            }
            if (response.statusCode() >= 500) {
                throw failed(SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true);
            }
            if (response.statusCode() >= 400 || response.body() == null) {
                throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
            }
            JsonNode responseJson = OBJECT_MAPPER.readTree(response.body());
            String encoded = responseJson.path("audioContent").asText(null);
            if (encoded == null) {
                throw failed(SpeechProviderException.Code.INVALID_RESPONSE, false);
            }
            byte[] audio = Base64.getDecoder().decode(encoded);
            long characters = request.canonicalText().codePointCount(0, request.canonicalText().length());
            return new SpeechResult(
                    response.headers().firstValue("x-request-id").orElse(null),
                    request.capability().modelSnapshot(), request.capability().region(),
                    request.providerVoice(), audio,
                    new ProviderUsage("INPUT_CHARACTER", characters, "AUDIO_BYTE", audio.length),
                    ModelEvidenceSource.QUALIFIED_VOICE_TIER);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw failed(SpeechProviderException.Code.AMBIGUOUS_TIMEOUT, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failed(SpeechProviderException.Code.PROVIDER_UNAVAILABLE, true);
        } catch (IOException | IllegalArgumentException exception) {
            throw failed(SpeechProviderException.Code.INVALID_RESPONSE, false);
        }
    }

    static String body(SpeechRequest request) {
        try {
            JsonNode controls = OBJECT_MAPPER.readTree(request.nativeControls());
            double rate = controls.path("speakingRate").asDouble(Double.NaN);
            if (!Double.isFinite(rate) || rate < 0.25 || rate > 2) {
                throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
            }
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "input", Map.of("text", request.canonicalText()),
                    "voice", Map.of("languageCode", language(request.providerVoice()),
                            "name", request.providerVoice()),
                    "audioConfig", Map.of("audioEncoding", "LINEAR16", "speakingRate", rate)));
        } catch (JsonProcessingException exception) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
    }

    private static String language(String voice) {
        if (voice == null || voice.length() < 5) {
            throw failed(SpeechProviderException.Code.INVALID_REQUEST, false);
        }
        return voice.substring(0, 5);
    }

    private static SpeechProviderException failed(SpeechProviderException.Code code, boolean retryable) {
        return new SpeechProviderException(code, retryable);
    }
}
