package dev.audiobook.platform.worktransport;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.admission")
public record WorkTransportProperties(String cloudProjectId, String workTopic) {}
