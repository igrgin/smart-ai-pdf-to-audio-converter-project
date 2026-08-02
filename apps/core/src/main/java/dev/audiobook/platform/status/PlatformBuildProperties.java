package dev.audiobook.platform.status;

import dev.audiobook.platform.status.service.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.build")
public record PlatformBuildProperties(String version, String revision) {}
