package dev.audiobook.platform.generation.internal.speech;

import dev.audiobook.platform.generation.internal.AudioGenerationProperties;
import dev.audiobook.platform.provider.SpeechProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

class SpeechResultValidationServiceTest {

    private final AudioGenerationProperties properties =
            AudioGenerationProperties.defaults(java.nio.file.Path.of("working"), java.nio.file.Path.of("final"));
    private final CanonicalSpeechDecoder decoder = mock(CanonicalSpeechDecoder.class);
    private final SpeechResultValidationService service =
            new SpeechResultValidationServiceImpl(properties, decoder);

    @Test
    void validFrozenRouteRawPcmBecomesCanonicalPcm() {
        byte[] pcm = new byte[48_000];
        for (int index = 0; index < pcm.length; index += 2) {
            short sample = (short) ((index % 200) * 20 - 2_000);
            pcm[index] = (byte) (sample & 0xff);
            pcm[index + 1] = (byte) ((sample >>> 8) & 0xff);
        }

        byte[] providerAudio = new byte[] {82, 73, 70, 70};
        given(decoder.decode(providerAudio)).willReturn(pcm);

        SpeechResultValidationService.ValidatedPcm result = service.validate(
                new SpeechResultValidationService.ExpectedRoute("model-v1", "eu", "cedar"),
                new SpeechProvider.SpeechResult("request-1", "model-v1", "eu", "cedar", providerAudio));

        assertThat(result.bytes()).containsExactly(pcm);
        assertThat(result.byteLength()).isEqualTo(48_000);
        assertThat(result.durationMs()).isEqualTo(1_000);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void providerDriftAndInvalidPcmFailClosed() {
        SpeechResultValidationService.ExpectedRoute route =
                new SpeechResultValidationService.ExpectedRoute("model-v1", "eu", "cedar");
        byte[] malformedAudio = new byte[] {1, 2, 3};
        given(decoder.decode(malformedAudio)).willThrow(new SpeechValidationException(
                SpeechValidationException.Code.INVALID_PCM));
        byte[] silence = new byte[48_000];
        given(decoder.decode(silence)).willReturn(silence);

        assertThatThrownBy(() -> service.validate(
                        route,
                        new SpeechProvider.SpeechResult(
                                "request-2", "different-model", "eu", "cedar", new byte[48_000])))
                .isInstanceOf(SpeechValidationException.class)
                .extracting(exception -> ((SpeechValidationException) exception).code())
                .isEqualTo(SpeechValidationException.Code.PROVIDER_DRIFT);
        assertThatThrownBy(() -> service.validate(
                        route,
                        new SpeechProvider.SpeechResult(
                                "request-3", "model-v1", "eu", "cedar", malformedAudio)))
                .isInstanceOf(SpeechValidationException.class)
                .extracting(exception -> ((SpeechValidationException) exception).code())
                .isEqualTo(SpeechValidationException.Code.INVALID_PCM);
        assertThatThrownBy(() -> service.validate(
                        route,
                        new SpeechProvider.SpeechResult(
                                "request-4", "model-v1", "eu", "cedar", new byte[48_000])))
                .isInstanceOf(SpeechValidationException.class)
                .extracting(exception -> ((SpeechValidationException) exception).code())
                .isEqualTo(SpeechValidationException.Code.INVALID_PCM);
    }
}
