package dev.audiobook.platform.admission.internal.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class PublicationSubmissionMaintenanceRunner {

    private final PublicationSubmissionService submissionService;

    @Scheduled(fixedDelayString = "${platform.admission.expiry-scan-delay:1m}")
    public void expireUploads() {
        submissionService.expireDue();
    }
}
