package dev.audiobook.platform.generation.internal.speech.validation;

import dev.audiobook.platform.generation.FfmpegTestToolchain;
import dev.audiobook.platform.generation.internal.AudioGenerationProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("itest")
@Testcontainers(disabledWithoutDocker = true)
class CanonicalSpeechDecoderITest {

    @TempDir
    Path scratch;

    @Test
    void arbitraryBinaryCannotMasqueradeAsCanonicalPcm() throws Exception {
        try (FfmpegTestToolchain toolchain = FfmpegTestToolchain.start(scratch)) {
            AudioGenerationProperties defaults =
                    AudioGenerationProperties.defaults(Path.of("working"), Path.of("final"));
            AudioGenerationProperties properties = properties(defaults, toolchain.command().toString());
            CanonicalSpeechDecoder decoder = new CanonicalSpeechDecoderImpl(properties);

            assertThatThrownBy(() -> decoder.decode(new byte[] {1, 2, 3, 4}))
                    .isInstanceOf(SpeechValidationException.class)
                    .extracting(exception -> ((SpeechValidationException) exception).code())
                    .isEqualTo(SpeechValidationException.Code.INVALID_PCM);
        }
    }

    private static AudioGenerationProperties properties(
            AudioGenerationProperties source, String ffmpegCommand) {
        return new AudioGenerationProperties(
                source.workingAssetPath(), source.finalAssetPath(), source.workingBucket(), source.finalBucket(),
                source.maximumSegmentCharacters(), source.openAiApiKey(), ffmpegCommand, source.commandTimeout(),
                source.sampleRate(), source.bitrateKbps(), source.targetLufs(), source.truePeakCeilingDbtp(),
                source.maximumPartDuration(), source.maximumPartBytes(), source.continuationSilence(),
                source.paragraphSilence(), source.structuralSilence(), source.chapterSilence(),
                source.workingAssetRetention());
    }
}
