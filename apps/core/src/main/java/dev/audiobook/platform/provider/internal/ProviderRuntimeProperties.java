package dev.audiobook.platform.provider.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.generation")
public record ProviderRuntimeProperties(String openAiApiKey, Duration commandTimeout) {
}
