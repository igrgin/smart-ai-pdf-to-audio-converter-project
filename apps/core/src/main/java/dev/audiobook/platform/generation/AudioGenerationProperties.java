package dev.audiobook.platform.generation;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.generation")
public record AudioGenerationProperties(
        Path workingAssetPath,
        Path finalAssetPath,
        String workingBucket,
        String finalBucket,
        int maximumSegmentCharacters,
        String openAiApiKey,
        String ffmpegCommand,
        Duration commandTimeout,
        int sampleRate,
        int bitrateKbps,
        double targetLufs,
        double truePeakCeilingDbtp,
        Duration maximumPartDuration,
        long maximumPartBytes,
        Duration continuationSilence,
        Duration paragraphSilence,
        Duration structuralSilence,
        Duration chapterSilence,
        Duration workingAssetRetention) {

    public static AudioGenerationProperties defaults(Path workingAssetPath, Path finalAssetPath) {
        return new AudioGenerationProperties(
                workingAssetPath,
                finalAssetPath,
                "local-working",
                "local-final",
                3_800,
                "",
                "ffmpeg",
                Duration.ofMinutes(2),
                24_000,
                64,
                -18.0,
                -1.5,
                Duration.ofMinutes(15),
                10L * 1024 * 1024,
                Duration.ofMillis(100),
                Duration.ofMillis(350),
                Duration.ofMillis(700),
                Duration.ofSeconds(1),
                Duration.ofDays(30));
    }
}
