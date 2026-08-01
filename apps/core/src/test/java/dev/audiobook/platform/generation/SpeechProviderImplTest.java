package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SpeechProviderImplTest {

    private final AudioGenerationProperties properties =
            propertiesWithApiKey();
    private final SpeechProvider provider = new SpeechProviderImpl(properties);

    @Test
    void rejectsASecretDestinationOutsideTheApprovedSpeechBoundary() {
        SpeechProvider.SpeechRequest request = new SpeechProvider.SpeechRequest(
                URI.create("https://attacker.invalid/collect"),
                "model-v1", "eu", "cedar", 1.0, "Narrate safely.", "Private prose");

        assertThatThrownBy(() -> provider.synthesize(request))
                .isInstanceOf(SpeechProviderException.class)
                .extracting(exception -> ((SpeechProviderException) exception).code())
                .isEqualTo(SpeechProviderException.Code.INVALID_REQUEST);
    }

    @Test
    void requestsSelfDescribingAudioForCanonicalDecoding() {
        SpeechProvider.SpeechRequest request = new SpeechProvider.SpeechRequest(
                URI.create("https://eu.api.openai.com/v1/audio/speech"),
                "model-v1", "eu", "cedar", 1.0, "Narrate safely.", "Private prose");

        assertThat(SpeechProviderImpl.body(request))
                .contains("\"response_format\":\"wav\"")
                .contains("\"model\":\"model-v1\"")
                .contains("\"voice\":\"cedar\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsRateLimitsAndDependencyFailuresWithoutLeakingContent() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        given(response.statusCode()).willReturn(429);
        given(client.send(any(), any(HttpResponse.BodyHandler.class))).willReturn(response);
        SpeechProvider tested = new SpeechProviderImpl(properties, client);
        SpeechProvider.SpeechRequest request = new SpeechProvider.SpeechRequest(
                URI.create("https://eu.api.openai.com/v1/audio/speech"),
                "model-v1", "eu", "cedar", 1.0, "Narrate safely.", "Private prose");

        assertThatThrownBy(() -> tested.synthesize(request))
                .isInstanceOf(SpeechProviderException.class)
                .satisfies(exception -> {
                    SpeechProviderException providerException = (SpeechProviderException) exception;
                    assertThat(providerException.code()).isEqualTo(SpeechProviderException.Code.RATE_LIMITED);
                    assertThat(providerException.retryable()).isTrue();
                    assertThat(providerException.getMessage()).doesNotContain("Private prose");
                });
    }

    private static AudioGenerationProperties propertiesWithApiKey() {
        AudioGenerationProperties source =
                AudioGenerationProperties.defaults(Path.of("working"), Path.of("final"));
        return new AudioGenerationProperties(
                source.workingAssetPath(), source.finalAssetPath(), source.workingBucket(), source.finalBucket(),
                source.maximumSegmentCharacters(), "test-only-key", source.ffmpegCommand(), source.commandTimeout(),
                source.sampleRate(), source.bitrateKbps(), source.targetLufs(), source.truePeakCeilingDbtp(),
                source.maximumPartDuration(), source.maximumPartBytes(), source.continuationSilence(),
                source.paragraphSilence(), source.structuralSilence(), source.chapterSilence(),
                source.workingAssetRetention());
    }
}
