package dev.audiobook.platform.entitlement.internal.subscription;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class DemonstrationSubscriptionProjectionRunner {

    private final DemonstrationSubscriptionProjector projector;
    private final DemonstrationSubscriptionProjectorControlService controlService;
    private final DemonstrationSubscriptionProperties properties;

    @Scheduled(fixedDelayString = "${platform.demonstration-subscription.projection-interval:5s}")
    public void projectPendingEvents() {
        if (properties.projectorEnabled() && !controlService.isPaused()) {
            projector.projectPending();
        }
    }
}
