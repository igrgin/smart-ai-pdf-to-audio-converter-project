package dev.audiobook.platform.provider.adapters.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.provider.ProviderUsage;
import dev.audiobook.platform.provider.SpeechProvider;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.net.URI;

@Service
@RequiredArgsConstructor
public class OpenAiProviderSpeechAdapterImpl implements OpenAiProviderSpeechAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final SpeechProvider speechProvider;

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public SpeechResult synthesize(SpeechRequest request) {
        Controls controls = controls(request.nativeControls());
        SpeechProvider.SpeechResult result =
                speechProvider.synthesize(
                        new SpeechProvider.SpeechRequest(
                                request.operationId(),
                                URI.create(request.capability().endpoint()),
                                request.capability().modelSnapshot(),
                                request.capability().region(),
                                request.providerVoice(),
                                controls.speed(),
                                controls.instructions(),
                                request.canonicalText()));
        long characters =
                request.canonicalText().codePointCount(0, request.canonicalText().length());
        return new SpeechResult(
                result.providerRequestId(),
                result.actualModel(),
                result.actualRegion(),
                result.actualVoice(),
                result.audio(),
                new ProviderUsage(
                        "INPUT_CHARACTER", characters, "AUDIO_BYTE", result.audio().length),
                ModelEvidenceSource.REQUESTED_MODEL);
    }

    private static Controls controls(String json) {
        try {
            JsonNode controls = OBJECT_MAPPER.readTree(json);
            double speed = controls.path("speed").asDouble(Double.NaN);
            String instructions = controls.path("instructions").asText(null);
            if (!Double.isFinite(speed)
                    || speed < 0.25
                    || speed > 4
                    || instructions == null
                    || instructions.isBlank()) {
                throw new IllegalArgumentException("Frozen OpenAI speech controls are invalid");
            }
            return new Controls(speed, instructions);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Frozen OpenAI speech controls are invalid", exception);
        }
    }

    private record Controls(double speed, String instructions) {}
}
