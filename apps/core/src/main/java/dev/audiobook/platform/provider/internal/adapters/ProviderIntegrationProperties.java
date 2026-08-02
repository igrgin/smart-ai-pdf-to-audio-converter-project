package dev.audiobook.platform.provider.internal.adapters;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.provider")
public record ProviderIntegrationProperties(String googleCloudProject) {
}
