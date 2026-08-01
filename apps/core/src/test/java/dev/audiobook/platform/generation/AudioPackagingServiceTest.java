package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AudioPackagingServiceTest {

    private static final int SAMPLE_RATE = 24_000;

    private final AudioGenerationProperties properties =
            AudioGenerationProperties.defaults(Path.of("working"), Path.of("final"));
    private final AudioPackagingService service = new AudioPackagingServiceImpl(properties);

    @Test
    void canonicalPcmIsOrderedWithOwnedSilenceAndReceivesOneAudiobookGainBeforeMp3Packaging() {
        AudioPackagingService.PackagingResult result = service.packageAudiobook(
                new AudioPackagingService.PackagingRequest(
                        UUID.fromString("01985f42-5f8d-7000-8000-000000000029"),
                        "a".repeat(64),
                        List.of(
                                new AudioPackagingService.Chapter(
                                        0,
                                        "First",
                                        List.of(new AudioPackagingService.AcceptedPcm(
                                                sine(3_000, 220),
                                                SpeechSegmentationService.BoundaryKind.CHAPTER))),
                                new AudioPackagingService.Chapter(
                                        1,
                                        "Second",
                                        List.of(new AudioPackagingService.AcceptedPcm(
                                                sine(3_000, 440),
                                                SpeechSegmentationService.BoundaryKind.CHAPTER))))));

        assertThat(result.profileVersion()).isEqualTo("mono-24k-mp3-v1");
        assertThat(result.chapters()).hasSize(2);
        assertThat(result.chapters())
                .extracting(AudioPackagingService.PackagedChapter::ordinal)
                .containsExactly(0, 1);
        assertThat(result.chapters())
                .flatExtracting(AudioPackagingService.PackagedChapter::parts)
                .hasSize(2)
                .allSatisfy(part -> {
                    assertThat(part.mimeType()).isEqualTo("audio/mpeg");
                    assertThat(part.bytes()).isNotEmpty();
                    assertThat(part.byteLength()).isLessThanOrEqualTo(properties.maximumPartBytes());
                    assertThat(part.durationMs()).isLessThanOrEqualTo(properties.maximumPartDuration().toMillis());
                    assertThat(part.sha256()).matches("[0-9a-f]{64}");
                });
        assertThat(result.totalDurationMs()).isEqualTo(8_000);
        assertThat(result.integratedLoudnessLufs()).isBetween(-18.6, -17.4);
        assertThat(result.truePeakDbtp()).isLessThanOrEqualTo(-1.4);
        assertThat(result.manifestDigest()).matches("[0-9a-f]{64}");
    }

    private static byte[] sine(int durationMs, double frequency) {
        int samples = SAMPLE_RATE * durationMs / 1_000;
        byte[] pcm = new byte[samples * 2];
        for (int index = 0; index < samples; index++) {
            short sample = (short) (8_000 * Math.sin(2 * Math.PI * frequency * index / SAMPLE_RATE));
            pcm[index * 2] = (byte) (sample & 0xff);
            pcm[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        return pcm;
    }
}
