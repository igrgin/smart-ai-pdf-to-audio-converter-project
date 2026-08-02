package dev.audiobook.platform.provider.internal.adapters.google;

import dev.audiobook.platform.provider.internal.governance.ProviderCapabilityService;
import dev.audiobook.platform.provider.internal.speech.ProviderSpeechAdapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleProviderSpeechAdapterImplTest {

    @Test
    void requestContainsOnlyCanonicalTextAndQualifiedNativeControls() {
        ProviderSpeechAdapter.SpeechRequest request = new ProviderSpeechAdapter.SpeechRequest(
                UUID.randomUUID().toString(), capability(), "en-GB-Neural2-F",
                "{\"speakingRate\":1.0}", "Necessary narration only.");

        assertThat(GoogleProviderSpeechAdapterImpl.body(request))
                .contains("Necessary narration only.")
                .contains("en-GB-Neural2-F")
                .contains("LINEAR16")
                .doesNotContain("listener", "title", "filename", "objectUrl", "credential");
    }

    private static ProviderCapabilityService.CapabilityProfile capability() {
        return new ProviderCapabilityService.CapabilityProfile(
                UUID.randomUUID(), "google-speech-eu-v1", "google",
                ProviderCapabilityService.ServiceKind.SPEECH,
                "https://eu-texttospeech.googleapis.com/v1/text:synthesize",
                "Neural2", "SYNCHRONOUS", "eu", "google-tts-eu-v1",
                Set.of(ProviderCapabilityService.InputKind.CANONICAL_TEXT),
                5_000, "UTF8_BYTE", "REQUEST_PER_MINUTE", 1_000, 60,
                "INPUT_CHARACTER", "application/json", "audio/wav",
                "{\"audioEncoding\":\"LINEAR16\"}",
                "{\"speakingRate\":{\"minimum\":0.25,\"maximum\":2.0}}",
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2027-02-01T00:00:00Z"));
    }
}
