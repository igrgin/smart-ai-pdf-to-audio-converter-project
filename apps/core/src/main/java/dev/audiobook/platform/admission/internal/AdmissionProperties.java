package dev.audiobook.platform.admission.internal;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.admission")
public record AdmissionProperties(
        String rightsTermsVersion,
        String rightsNoticeVersion,
        long maximumUploadBytes,
        int uploadChunkBytes,
        Duration uploadSessionValidity,
        Duration quarantineRetention,
        long reservedCharacters,
        long reservedProviderCostMicros,
        String provider,
        String rateCardVersion,
        String uploadTokenSecret,
        Path quarantinePath,
        String cloudProjectId,
        String workingBucket,
        String workTopic,
        String pushAudience,
        String pushServiceAccount) {
}
