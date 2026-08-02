package dev.audiobook.platform.generation.speech.validation.service;

import dev.audiobook.platform.generation.AudioGenerationProperties;
import dev.audiobook.platform.generation.shared.digest.Sha256Digest;
import dev.audiobook.platform.generation.speech.validation.*;
import dev.audiobook.platform.provider.SpeechProvider;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SpeechResultValidationServiceImpl implements SpeechResultValidationService {

    private final AudioGenerationProperties properties;
    private final CanonicalSpeechDecoder speechDecoder;

    @Override
    public ValidatedPcm validate(ExpectedRoute expectedRoute, SpeechProvider.SpeechResult result) {
        Objects.requireNonNull(expectedRoute, "expectedRoute");
        Objects.requireNonNull(result, "result");
        if (!Objects.equals(expectedRoute.model(), result.actualModel())
                || !Objects.equals(expectedRoute.region(), result.actualRegion())
                || !Objects.equals(expectedRoute.voice(), result.actualVoice())) {
            throw new SpeechValidationException(SpeechValidationException.Code.PROVIDER_DRIFT);
        }
        byte[] pcm = speechDecoder.decode(result.audio());
        if (pcm.length < 2
                || pcm.length % 2 != 0
                || properties.sampleRate() <= 0
                || onlySilence(pcm)) {
            throw new SpeechValidationException(SpeechValidationException.Code.INVALID_PCM);
        }
        long durationMs = Math.multiplyExact(pcm.length / 2L, 1_000L) / properties.sampleRate();
        if (durationMs < 50 || durationMs > DurationLimit.MAXIMUM_MILLISECONDS) {
            throw new SpeechValidationException(SpeechValidationException.Code.INVALID_PCM);
        }
        return new ValidatedPcm(pcm, Sha256Digest.of(pcm), pcm.length, durationMs);
    }

    private static boolean onlySilence(byte[] pcm) {
        for (int index = 0; index < pcm.length; index += 2) {
            int sample = (pcm[index] & 0xff) | (pcm[index + 1] << 8);
            if (Math.abs((short) sample) > 1) {
                return false;
            }
        }
        return true;
    }

    private static final class DurationLimit {
        private static final long MAXIMUM_MILLISECONDS = 15L * 60L * 1_000L;

        private DurationLimit() {}
    }
}
