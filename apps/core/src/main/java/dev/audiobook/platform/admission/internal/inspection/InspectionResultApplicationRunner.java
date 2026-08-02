package dev.audiobook.platform.admission.internal.inspection;

import dev.audiobook.platform.admission.internal.submission.PublicationSubmissionService;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class InspectionResultApplicationRunner {

    private final PublicationSubmissionService submissionService;

    @Scheduled(fixedDelayString = "${platform.admission.inspection-result-delay:1s}")
    public void apply() {
        submissionService.applyInspectionResults();
    }
}
