package dev.audiobook.platform.retention.tombstone;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.retention.RetentionProperties;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;

@Component
@Profile("!prod")
public class FilesystemTombstoneRegistry implements TombstoneRegistry {

    private final Path root;
    private final ObjectMapper objectMapper;

    public FilesystemTombstoneRegistry(RetentionProperties properties) throws IOException {
        root = properties.tombstoneRegistryPath().toAbsolutePath().normalize();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        Files.createDirectories(root);
    }

    @Override
    public void append(TombstoneRecord tombstone) {
        Path target = root.resolve(tombstone.tombstoneId() + ".json");
        try {
            byte[] content = objectMapper.writeValueAsBytes(tombstone);
            try {
                Files.write(target, content, StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException replay) {
                if (!MessageDigest.isEqual(Files.readAllBytes(target), content)) {
                    throw new IllegalStateException("Tombstone registry entry conflicts");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Tombstone registry is unavailable", exception);
        }
    }

    @Override
    public List<TombstoneRecord> entries() {
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .sorted(Comparator.comparing(TombstoneRecord::createdAt))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Tombstone registry is unavailable", exception);
        }
    }

    private TombstoneRecord read(Path path) {
        try {
            return objectMapper.readValue(Files.readAllBytes(path), TombstoneRecord.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Tombstone registry entry is invalid", exception);
        }
    }
}
