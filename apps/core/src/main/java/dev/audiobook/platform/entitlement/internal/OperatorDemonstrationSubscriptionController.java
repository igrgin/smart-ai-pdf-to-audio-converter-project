package dev.audiobook.platform.entitlement.internal;

import dev.audiobook.platform.entitlement.internal.subscription.DemonstrationSubscriptionProjector;
import dev.audiobook.platform.entitlement.internal.subscription.DemonstrationSubscriptionProjectorControlService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator/demonstration-subscriptions/projector")
@RequiredArgsConstructor
public class OperatorDemonstrationSubscriptionController {

    private final DemonstrationSubscriptionProjectorControlService controlService;
    private final DemonstrationSubscriptionProjector projector;

    @PostMapping("/pause")
    public ResponseEntity<Void> pause() {
        controlService.pause();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/resume")
    public ResponseEntity<Void> resume() {
        controlService.resume();
        projector.projectPending();
        return ResponseEntity.noContent().build();
    }
}
