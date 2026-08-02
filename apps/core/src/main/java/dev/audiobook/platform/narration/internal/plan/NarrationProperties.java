package dev.audiobook.platform.narration.internal.plan;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.narration")
public record NarrationProperties(Path workingAssetPath, String workingBucket) {
}
