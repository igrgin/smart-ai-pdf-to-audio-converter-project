package dev.audiobook.platform.generation;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

final class FfmpegTestToolchain implements AutoCloseable {

    private static final ImageFromDockerfile IMAGE = new ImageFromDockerfile(
                    "folio-ffmpeg-toolchain-itest", false)
            .withDockerfileFromBuilder(builder -> builder
                    .from("alpine:3.22")
                    .run("apk add --no-cache ffmpeg")
                    .cmd("tail", "-f", "/dev/null")
                    .build());

    private final GenericContainer<?> container;
    private final Path command;

    private FfmpegTestToolchain(GenericContainer<?> container, Path command) {
        this.container = container;
        this.command = command;
    }

    static FfmpegTestToolchain start(Path wrapperDirectory) throws Exception {
        String temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath()
                .normalize()
                .toString();
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withFileSystemBind(temporaryDirectory, temporaryDirectory, BindMode.READ_WRITE);
        container.start();
        Path command = wrapperDirectory.resolve("ffmpeg-container");
        Files.writeString(
                command,
                "#!/bin/sh\nexec " + shellQuote(executableOnPath("docker").toString())
                        + " exec " + shellQuote(container.getContainerId()) + " ffmpeg \"$@\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        return new FfmpegTestToolchain(container, command);
    }

    Path command() {
        return command;
    }

    @Override
    public void close() {
        container.stop();
    }

    private static Path executableOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("PATH is unavailable while resolving " + executable);
        }
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            Path candidate = Path.of(directory).resolve(executable).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(executable + " is not available on PATH");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
