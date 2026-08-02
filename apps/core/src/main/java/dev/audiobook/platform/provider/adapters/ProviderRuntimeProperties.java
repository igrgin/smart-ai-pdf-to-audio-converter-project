package dev.audiobook.platform.provider.adapters;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("platform.generation")
public record ProviderRuntimeProperties(String openAiApiKey, Duration commandTimeout) {}
