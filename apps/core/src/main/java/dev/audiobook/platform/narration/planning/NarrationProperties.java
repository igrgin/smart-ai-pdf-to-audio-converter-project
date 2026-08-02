package dev.audiobook.platform.narration.planning;

import dev.audiobook.platform.narration.planning.service.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("platform.narration")
public record NarrationProperties(Path workingAssetPath, String workingBucket) {}
