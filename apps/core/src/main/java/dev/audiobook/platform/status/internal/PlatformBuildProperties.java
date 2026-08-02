package dev.audiobook.platform.status.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.build")
public record PlatformBuildProperties(String version, String revision) {
}
