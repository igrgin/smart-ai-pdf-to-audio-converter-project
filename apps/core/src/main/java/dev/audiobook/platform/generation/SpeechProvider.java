package dev.audiobook.platform.generation;

import java.net.URI;

public interface SpeechProvider {

    SpeechResult synthesize(SpeechRequest request);

    record SpeechRequest(
            URI endpoint,
            String model,
            String region,
            String voice,
            double speed,
            String instructions,
            String spokenText) {
    }

    record SpeechResult(
            String providerRequestId,
            String actualModel,
            String actualRegion,
            String actualVoice,
            byte[] audio) {
        public SpeechResult {
            audio = audio == null ? new byte[0] : audio.clone();
        }

        @Override
        public byte[] audio() {
            return audio.clone();
        }
    }
}
