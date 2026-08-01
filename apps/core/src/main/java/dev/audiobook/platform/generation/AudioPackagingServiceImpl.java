package dev.audiobook.platform.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AudioPackagingServiceImpl implements AudioPackagingService {

    private static final String PROFILE_VERSION = "mono-24k-mp3-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AudioGenerationProperties properties;

    @Override
    public PackagingResult packageAudiobook(PackagingRequest request) {
        validate(request);
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("folio-audio-package-");
            Assembly assembly = assemble(request);
            Path input = scratch.resolve("assembled.pcm");
            Path normalized = scratch.resolve("normalized.pcm");
            Files.write(input, assembly.pcm());

            Loudness inputLoudness = measure(input);
            CommandResult normalization = run(List.of(
                    properties.ffmpegCommand(),
                    "-hide_banner", "-nostdin", "-y",
                    "-f", "s16le", "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                    "-i", input.toString(),
                    "-af", normalizationFilter(inputLoudness),
                    "-f", "s16le", "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                    normalized.toString()));
            Loudness outputLoudness = parseLoudness(normalization.output(), "output_i", "output_tp");
            validateLoudness(outputLoudness);
            byte[] normalizedPcm = Files.readAllBytes(normalized);
            if (normalizedPcm.length != assembly.pcm().length) {
                throw new AudioPackagingException("Whole-audiobook normalization changed canonical duration");
            }

            List<PackagedChapter> chapters = encodeChapters(scratch, assembly, normalizedPcm);
            long totalBytes = chapters.stream()
                    .flatMap(chapter -> chapter.parts().stream())
                    .mapToLong(PackagedPart::byteLength)
                    .sum();
            long totalDurationMs = pcmDuration(normalizedPcm.length);
            double appliedGain = outputLoudness.integratedLufs() - inputLoudness.integratedLufs();
            String manifestDigest = manifestDigest(
                    request.recipeDigest(), chapters, totalDurationMs, totalBytes, outputLoudness);
            return new PackagingResult(
                    PROFILE_VERSION,
                    chapters,
                    totalDurationMs,
                    totalBytes,
                    outputLoudness.integratedLufs(),
                    outputLoudness.truePeakDbtp(),
                    appliedGain,
                    manifestDigest);
        } catch (IOException exception) {
            throw new AudioPackagingException("Audio packaging storage is unavailable", exception);
        } finally {
            deleteScratch(scratch);
        }
    }

    private Assembly assemble(PackagingRequest request) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        List<ChapterLayout> chapters = new ArrayList<>();
        int expectedChapter = 0;
        long maximumPartPcmBytes = Math.multiplyExact(
                properties.maximumPartDuration().toMillis(), properties.sampleRate() * 2L) / 1_000L;
        for (Chapter chapter : request.chapters()) {
            if (chapter == null
                    || chapter.ordinal() != expectedChapter++
                    || chapter.displayTitle() == null
                    || chapter.displayTitle().isBlank()
                    || chapter.segments().isEmpty()) {
                throw new AudioPackagingException("Packaged chapters must be non-empty, gapless and ordered");
            }
            long chapterStart = all.size();
            long partStart = chapterStart;
            List<PartSpan> partSpans = new ArrayList<>();
            for (AcceptedPcm segment : chapter.segments()) {
                byte[] pcm = segment.bytes();
                if (pcm.length == 0 || pcm.length % 2 != 0 || segment.boundaryKind() == null) {
                    throw new AudioPackagingException("Accepted segment PCM is not canonical");
                }
                long unitBytes = Math.addExact(pcm.length, silenceBytes(segment.boundaryKind()));
                if (unitBytes > maximumPartPcmBytes) {
                    throw new AudioPackagingException("One semantic audio unit exceeds the part duration limit");
                }
                if (all.size() > partStart && all.size() - partStart + unitBytes > maximumPartPcmBytes) {
                    partSpans.add(new PartSpan(partStart, all.size()));
                    partStart = all.size();
                }
                all.write(pcm);
                all.write(new byte[Math.toIntExact(silenceBytes(segment.boundaryKind()))]);
            }
            if (chapter.segments().getLast().boundaryKind()
                    != SpeechSegmentationService.BoundaryKind.CHAPTER) {
                throw new AudioPackagingException("Every approved chapter must end at a chapter boundary");
            }
            partSpans.add(new PartSpan(partStart, all.size()));
            chapters.add(new ChapterLayout(
                    chapter.ordinal(), chapter.displayTitle(), chapterStart, all.size(), partSpans));
        }
        return new Assembly(all.toByteArray(), chapters);
    }

    private List<PackagedChapter> encodeChapters(Path scratch, Assembly assembly, byte[] normalizedPcm)
            throws IOException {
        List<PackagedChapter> chapters = new ArrayList<>();
        for (ChapterLayout layout : assembly.chapters()) {
            List<PackagedPart> parts = new ArrayList<>();
            int partOrdinal = 0;
            for (PartSpan span : layout.parts()) {
                byte[] partPcm = Arrays.copyOfRange(
                        normalizedPcm, Math.toIntExact(span.start()), Math.toIntExact(span.end()));
                Path pcmPath = scratch.resolve("part-" + layout.ordinal() + "-" + partOrdinal + ".pcm");
                Path mp3Path = scratch.resolve("part-" + layout.ordinal() + "-" + partOrdinal + ".mp3");
                Files.write(pcmPath, partPcm);
                run(List.of(
                        properties.ffmpegCommand(),
                        "-hide_banner", "-nostdin", "-y",
                        "-f", "s16le", "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                        "-i", pcmPath.toString(),
                        "-map_metadata", "-1", "-codec:a", "libmp3lame",
                        "-b:a", properties.bitrateKbps() + "k", "-write_xing", "0",
                        "-f", "mp3", mp3Path.toString()));
                byte[] mp3 = Files.readAllBytes(mp3Path);
                long durationMs = probeDuration(
                        mp3Path,
                        scratch.resolve("probe-" + layout.ordinal() + "-" + partOrdinal + ".pcm"),
                        pcmDuration(partPcm.length));
                if (mp3.length > properties.maximumPartBytes()
                        || durationMs > properties.maximumPartDuration().toMillis()) {
                    throw new AudioPackagingException("Encoded audiobook part exceeds its frozen bound");
                }
                parts.add(new PackagedPart(
                        partOrdinal++,
                        "audio/mpeg",
                        mp3,
                        mp3.length,
                        durationMs,
                        SpeechSegmentationServiceImpl.sha256Bytes(mp3)));
            }
            chapters.add(new PackagedChapter(
                    layout.ordinal(),
                    layout.displayTitle(),
                    pcmDuration(layout.start()),
                    pcmDuration(layout.end() - layout.start()),
                    parts));
        }
        return List.copyOf(chapters);
    }

    private Loudness measure(Path input) {
        CommandResult result = run(List.of(
                properties.ffmpegCommand(),
                "-hide_banner", "-nostdin", "-y",
                "-f", "s16le", "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                "-i", input.toString(),
                "-af", loudnessPrefix() + ":print_format=json",
                "-f", "null", "-"));
        return parseLoudness(result.output(), "input_i", "input_tp");
    }

    private String normalizationFilter(Loudness measured) {
        return loudnessPrefix()
                + ":measured_I=" + measured.integratedLufs()
                + ":measured_TP=" + measured.truePeakDbtp()
                + ":measured_LRA=" + measured.loudnessRange()
                + ":measured_thresh=" + measured.threshold()
                + ":offset=" + measured.targetOffset()
                + ":linear=true:print_format=json";
    }

    private String loudnessPrefix() {
        return "loudnorm=I=" + properties.targetLufs()
                + ":TP=" + properties.truePeakCeilingDbtp()
                + ":LRA=11";
    }

    private static Loudness parseLoudness(String output, String loudnessField, String peakField) {
        int start = output.lastIndexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AudioPackagingException("FFmpeg did not report loudness measurements");
        }
        try {
            JsonNode json = OBJECT_MAPPER.readTree(output.substring(start, end + 1));
            return new Loudness(
                    number(json, loudnessField),
                    number(json, peakField),
                    number(json, loudnessField.startsWith("input") ? "input_lra" : "output_lra"),
                    number(json, loudnessField.startsWith("input") ? "input_thresh" : "output_thresh"),
                    number(json, "target_offset"));
        } catch (IOException | NumberFormatException exception) {
            throw new AudioPackagingException("FFmpeg loudness measurements are invalid", exception);
        }
    }

    private static double number(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null) {
            throw new NumberFormatException(field);
        }
        double number = Double.parseDouble(value.asText());
        if (!Double.isFinite(number)) {
            throw new NumberFormatException(field);
        }
        return number;
    }

    private void validateLoudness(Loudness loudness) {
        if (Math.abs(loudness.integratedLufs() - properties.targetLufs()) > 0.6
                || loudness.truePeakDbtp() > properties.truePeakCeilingDbtp() + 0.1) {
            throw new AudioPackagingException("Whole-audiobook loudness profile validation failed");
        }
    }

    private long probeDuration(Path mp3, Path decoded, long expectedDurationMs) {
        run(List.of(
                properties.ffmpegCommand(),
                "-hide_banner", "-nostdin", "-y", "-i", mp3.toString(),
                "-f", "s16le", "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                decoded.toString()));
        try {
            long durationMs = pcmDuration(Files.size(decoded));
            if (durationMs <= 0 || Math.abs(durationMs - expectedDurationMs) > 100) {
                throw new AudioPackagingException("Encoded part duration does not reconcile");
            }
            return durationMs;
        } catch (IOException exception) {
            throw new AudioPackagingException("Encoded part duration is invalid", exception);
        }
    }

    private CommandResult run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
            boolean completed = process.waitFor(properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new AudioPackagingException("Audio toolchain command timed out");
            }
            String captured = new String(output.join(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AudioPackagingException("Audio toolchain command failed");
            }
            return new CommandResult(captured);
        } catch (IOException exception) {
            throw new AudioPackagingException("Audio toolchain is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AudioPackagingException("Audio toolchain command was interrupted", exception);
        }
    }

    private long silenceBytes(SpeechSegmentationService.BoundaryKind kind) {
        long milliseconds = switch (kind) {
            case LIMIT_CONTINUATION -> properties.continuationSilence().toMillis();
            case PARAGRAPH -> properties.paragraphSilence().toMillis();
            case STRUCTURAL_SECTION -> properties.structuralSilence().toMillis();
            case CHAPTER -> properties.chapterSilence().toMillis();
        };
        return Math.multiplyExact(milliseconds, properties.sampleRate() * 2L) / 1_000L;
    }

    private long pcmDuration(long bytes) {
        return Math.multiplyExact(bytes / 2L, 1_000L) / properties.sampleRate();
    }

    private static String manifestDigest(
            String recipeDigest,
            List<PackagedChapter> chapters,
            long totalDurationMs,
            long totalBytes,
            Loudness loudness) {
        StringBuilder canonical = new StringBuilder(recipeDigest)
                .append('|').append(PROFILE_VERSION)
                .append('|').append(totalDurationMs)
                .append('|').append(totalBytes)
                .append('|').append(loudness.integratedLufs())
                .append('|').append(loudness.truePeakDbtp());
        for (PackagedChapter chapter : chapters) {
            canonical.append('|').append(chapter.ordinal())
                    .append('|').append(chapter.startMs())
                    .append('|').append(chapter.durationMs());
            for (PackagedPart part : chapter.parts()) {
                canonical.append('|').append(part.ordinal())
                        .append('|').append(part.durationMs())
                        .append('|').append(part.byteLength())
                        .append('|').append(part.sha256());
            }
        }
        return SpeechSegmentationServiceImpl.sha256(canonical.toString());
    }

    private static void validate(PackagingRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.conversionId(), "conversionId");
        if (request.recipeDigest() == null
                || !request.recipeDigest().matches("[0-9a-f]{64}")
                || request.chapters().isEmpty()) {
            throw new AudioPackagingException("Frozen recipe and accepted chapters are required");
        }
    }

    private static void deleteScratch(Path scratch) {
        if (scratch == null) {
            return;
        }
        try (var paths = Files.walk(scratch)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Scratch cleanup is retried by the operating system's temporary-file policy.
                }
            });
        } catch (IOException ignored) {
            // Scratch cleanup is retried by the operating system's temporary-file policy.
        }
    }

    private record Assembly(byte[] pcm, List<ChapterLayout> chapters) {
    }

    private record ChapterLayout(
            int ordinal, String displayTitle, long start, long end, List<PartSpan> parts) {
    }

    private record PartSpan(long start, long end) {
    }

    private record Loudness(
            double integratedLufs,
            double truePeakDbtp,
            double loudnessRange,
            double threshold,
            double targetOffset) {
    }

    private record CommandResult(String output) {
    }
}
