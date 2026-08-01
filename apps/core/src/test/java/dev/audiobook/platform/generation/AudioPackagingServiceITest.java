package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("itest")
@Testcontainers(disabledWithoutDocker = true)
class AudioPackagingServiceITest {

    private static final int SAMPLE_RATE = 24_000;

    @TempDir
    Path scratch;

    @Test
    void canonicalPcmIsOrderedWithOwnedSilenceAndReceivesOneAudiobookGainBeforeMp3Packaging()
            throws Exception {
        try (FfmpegTestToolchain toolchain = FfmpegTestToolchain.start(scratch)) {
            AudioGenerationProperties properties = properties(toolchain.command().toString());
            AudioPackagingService service = new AudioPackagingServiceImpl(properties);
        AudioPackagingService.PackagingResult result = service.packageAudiobook(
                new AudioPackagingService.PackagingRequest(
                        UUID.fromString("01985f42-5f8d-7000-8000-000000000029"),
                        "a".repeat(64),
                        "mono-24k-mp3-v1",
                        "speech-worker-ffmpeg-v1",
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
        assertThat(result.totalDurationMs()).isBetween(8_000L, 8_200L);
        assertThat(result.chapters())
                .satisfiesExactly(
                        first -> assertThat(first.startMs()).isZero(),
                        second -> assertThat(second.startMs()).isEqualTo(result.chapters().getFirst().durationMs()));
        assertThat(result.chapters().stream().mapToLong(AudioPackagingService.PackagedChapter::durationMs).sum())
                .isEqualTo(result.totalDurationMs());
        assertThat(result.integratedLoudnessLufs()).isBetween(-18.6, -17.4);
        assertThat(result.truePeakDbtp()).isLessThanOrEqualTo(-1.4);
        assertThat(result.manifestDigest()).matches("[0-9a-f]{64}");
        }
    }

    @Test
    void staleAudioPolicyOrToolchainFailsClosed() {
        AudioPackagingService service = new AudioPackagingServiceImpl(properties("unused-ffmpeg"));
        List<AudioPackagingService.Chapter> chapters = List.of(new AudioPackagingService.Chapter(
                0,
                "Only",
                List.of(new AudioPackagingService.AcceptedPcm(
                        sine(1_000, 220), SpeechSegmentationService.BoundaryKind.CHAPTER))));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.packageAudiobook(
                        new AudioPackagingService.PackagingRequest(
                                UUID.randomUUID(), "a".repeat(64), "stale-profile", "speech-worker-ffmpeg-v1", chapters)))
                .isInstanceOf(AudioPackagingException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.packageAudiobook(
                        new AudioPackagingService.PackagingRequest(
                                UUID.randomUUID(), "a".repeat(64), "mono-24k-mp3-v1", "stale-toolchain", chapters)))
                .isInstanceOf(AudioPackagingException.class);
    }

    private static AudioGenerationProperties properties(String ffmpegCommand) {
        AudioGenerationProperties source =
                AudioGenerationProperties.defaults(Path.of("working"), Path.of("final"));
        return new AudioGenerationProperties(
                source.workingAssetPath(), source.finalAssetPath(), source.workingBucket(), source.finalBucket(),
                source.maximumSegmentCharacters(), source.openAiApiKey(), ffmpegCommand, source.commandTimeout(),
                source.sampleRate(), source.bitrateKbps(), source.targetLufs(), source.truePeakCeilingDbtp(),
                source.maximumPartDuration(), source.maximumPartBytes(), source.continuationSilence(),
                source.paragraphSilence(), source.structuralSilence(), source.chapterSilence(),
                source.workingAssetRetention());
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
