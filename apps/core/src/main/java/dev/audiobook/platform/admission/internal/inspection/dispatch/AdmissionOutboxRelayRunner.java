package dev.audiobook.platform.admission.internal.inspection.dispatch;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@Profile({"dev", "prod"})
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class AdmissionOutboxRelayRunner {

    private final AdmissionOutboxRelayService relayService;

    @Scheduled(fixedDelayString = "${platform.admission.outbox-relay-delay:1s}")
    public void relay() {
        relayService.relayPending();
    }
}
