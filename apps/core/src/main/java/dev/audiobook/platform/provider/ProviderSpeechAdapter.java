package dev.audiobook.platform.provider;

public interface ProviderSpeechAdapter {

    String provider();

    SpeechResult synthesize(SpeechRequest request);

    record SpeechRequest(
            String operationId,
            ProviderCapabilityService.CapabilityProfile capability,
            String providerVoice,
            String nativeControls,
            String canonicalText) {
        public SpeechRequest {
            java.util.UUID.fromString(operationId);
            java.util.Objects.requireNonNull(capability, "capability");
            java.util.Objects.requireNonNull(providerVoice, "providerVoice");
            java.util.Objects.requireNonNull(nativeControls, "nativeControls");
            java.util.Objects.requireNonNull(canonicalText, "canonicalText");
        }
    }

    record SpeechResult(
            String providerRequestId,
            String actualModel,
            String actualRegion,
            String actualVoice,
            byte[] audio,
            ProviderUsage usage,
            ModelEvidenceSource modelEvidenceSource) {
        public SpeechResult {
            audio = audio == null ? new byte[0] : audio.clone();
        }

        @Override
        public byte[] audio() {
            return audio.clone();
        }
    }

    enum ModelEvidenceSource {
        REQUESTED_MODEL,
        QUALIFIED_VOICE_TIER
    }
}
