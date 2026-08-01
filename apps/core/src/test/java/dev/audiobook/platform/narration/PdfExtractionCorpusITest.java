package dev.audiobook.platform.narration;

import com.github.dockerjava.api.model.Capability;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@ActiveProfiles("itest")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "PDF_TOOLCHAIN_IMAGE", matches = ".+")
class PdfExtractionCorpusITest extends PdfExtractionCorpusContract {

    @Override
    protected Toolchain toolchain(Corpus corpus) throws Exception {
        Files.setPosixFilePermissions(scratch, PosixFilePermissions.fromString("rwxrwxrwx"));
        String image = System.getenv("PDF_TOOLCHAIN_IMAGE");
        var toolchain = new GenericContainer<>(DockerImageName.parse(image))
                .withFileSystemBind(scratch.toString(), scratch.toString(), BindMode.READ_WRITE)
                .withEnv("HOME", "/tmp")
                .withCreateContainerCmdModifier(command -> {
                    command.withEntrypoint("tail").withCmd("-f", "/dev/null");
                    command.getHostConfig()
                            .withNetworkMode("none")
                            .withReadonlyRootfs(true)
                            .withCapDrop(Capability.ALL)
                            .withSecurityOpts(List.of("no-new-privileges"))
                            .withPidsLimit(256L)
                            .withMemory(4L * 1024 * 1024 * 1024)
                            .withNanoCPUs(2_000_000_000L)
                            .withTmpFs(Map.of("/tmp", "rw,size=1g,mode=1777"));
                });
        toolchain.start();

        Path docker = executableOnPath("docker");
        Path docling = wrapper(
                "docling-container",
                docker,
                toolchain.getContainerId(),
                "python3 /app/bin/docling_extract.py \"$2\" \"$3\" \"$4\"");
        Path tesseract = wrapper(
                "tesseract-container",
                docker,
                toolchain.getContainerId(),
                "tesseract \"$@\"");
        return new Toolchain(docling, tesseract, Duration.ofMinutes(2), toolchain::stop);
    }

    private Path wrapper(String name, Path docker, String containerId, String command) throws Exception {
        return executable(
                name,
                "#!/bin/sh\nexec " + shellQuote(docker.toString()) + " exec " + shellQuote(containerId) + " "
                        + command + "\n");
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
