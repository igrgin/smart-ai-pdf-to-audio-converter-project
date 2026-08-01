package dev.audiobook.platform.generation;

public interface SpeechResultValidationService {

    ValidatedPcm validate(ExpectedRoute expectedRoute, SpeechProvider.SpeechResult result);

    record ExpectedRoute(String model, String region, String voice) {
    }

    record ValidatedPcm(byte[] bytes, String sha256, long byteLength, long durationMs) {
        public ValidatedPcm {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
