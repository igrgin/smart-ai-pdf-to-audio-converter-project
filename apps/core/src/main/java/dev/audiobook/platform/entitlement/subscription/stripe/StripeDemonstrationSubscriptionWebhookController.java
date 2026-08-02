package dev.audiobook.platform.entitlement.subscription.stripe;

import dev.audiobook.platform.entitlement.subscription.DemonstrationSubscriptionProjector;
import dev.audiobook.platform.entitlement.subscription.DemonstrationSubscriptionProperties;
import dev.audiobook.platform.entitlement.subscription.service.DemonstrationSubscriptionProjectorControlService;
import dev.audiobook.platform.entitlement.subscription.stripe.service.*;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(StripeDemonstrationSubscriptionWebhookController.EVENT_PATH)
@RequiredArgsConstructor
public class StripeDemonstrationSubscriptionWebhookController {

    public static final String EVENT_PATH = "/api/v1/integrations/stripe/events";

    private final StripeWebhookVerifier webhookVerifier;
    private final StripeEventInboxService inboxService;
    private final DemonstrationSubscriptionProjector projector;
    private final DemonstrationSubscriptionProjectorControlService projectorControlService;
    private final DemonstrationSubscriptionProperties properties;

    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
        inboxService.accept(webhookVerifier.verify(payload, signature));
        if (properties.projectorEnabled() && !projectorControlService.isPaused()) {
            projector.projectPending();
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(StripeWebhookVerificationException.class)
    public ResponseEntity<Void> invalidWebhook() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
