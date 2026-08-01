package dev.audiobook.platform.generation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CanonicalSpeechDecoderImpl implements CanonicalSpeechDecoder {

    private final AudioGenerationProperties properties;

    @Override
    public byte[] decode(byte[] providerAudio) {
        if (providerAudio == null || providerAudio.length == 0) {
            throw invalid();
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("folio-speech-decode-");
            Path source = scratch.resolve("provider-audio");
            Path canonical = scratch.resolve("canonical.pcm");
            Files.write(source, providerAudio);
            Process process = new ProcessBuilder(List.of(
                            properties.ffmpegCommand(),
                            "-hide_banner", "-nostdin", "-v", "error", "-y",
                            "-i", source.toString(),
                            "-map_metadata", "-1", "-f", "s16le",
                            "-ar", Integer.toString(properties.sampleRate()), "-ac", "1",
                            canonical.toString()))
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return process.getInputStream().readAllBytes();
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
            boolean completed = process.waitFor(
                    properties.commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw invalid();
            }
            if (process.exitValue() != 0 || output.join().length > 4_096 || !Files.isRegularFile(canonical)) {
                throw invalid();
            }
            byte[] pcm = Files.readAllBytes(canonical);
            if (pcm.length == 0 || pcm.length % 2 != 0) {
                throw invalid();
            }
            return pcm;
        } catch (IOException exception) {
            throw invalid();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw invalid();
        } catch (java.util.concurrent.CompletionException exception) {
            throw invalid();
        } finally {
            deleteScratch(scratch);
        }
    }

    private static SpeechValidationException invalid() {
        return new SpeechValidationException(SpeechValidationException.Code.INVALID_PCM);
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
                    // The operating system's temporary-file policy retries scratch cleanup.
                }
            });
        } catch (IOException ignored) {
            // The operating system's temporary-file policy retries scratch cleanup.
        }
    }
}
