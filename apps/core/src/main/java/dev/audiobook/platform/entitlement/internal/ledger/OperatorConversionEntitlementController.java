package dev.audiobook.platform.entitlement.internal.ledger;

import dev.audiobook.platform.entitlement.ConversionEntitlementService;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operator")
@RequiredArgsConstructor
public class OperatorConversionEntitlementController {

    private final ConversionEntitlementService entitlementService;

    @PostMapping("/listeners/{listenerId}/conversion-entitlements/free-grants")
    public ResponseEntity<FreeGrantView> approveFreeGrant(
            @PathVariable UUID listenerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody FreeGrantCommand command) {
        if (command.approvalReference() == null || command.approvalReference().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ConversionEntitlementService.FreeGrant grant = entitlementService.approveFreeGrant(
                listenerId, command.approvalReference(), idempotencyKey);
        HttpStatus status = grant.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(FreeGrantView.from(grant));
    }

    @GetMapping("/conversion-entitlements/provider-spend/{provider}")
    public ResponseEntity<ProviderSpendView> providerSpend(@PathVariable String provider) {
        ConversionEntitlementService.ProviderSpend spend = entitlementService.providerSpend(provider);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ProviderSpendView(provider, spend.reservedMicros(), spend.committedMicros()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<OperatorError> invalidCommand() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new OperatorError("INVALID_ENTITLEMENT_COMMAND"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<OperatorError> conflictingCommand() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new OperatorError("FREE_GRANT_CONFLICT"));
    }

    public record FreeGrantCommand(String approvalReference) {
    }

    public record FreeGrantView(
            UUID grantId,
            long grantedCharacters,
            Instant validFrom,
            Instant validUntil,
            boolean created) {

        static FreeGrantView from(ConversionEntitlementService.FreeGrant grant) {
            return new FreeGrantView(
                    grant.grantId(),
                    grant.grantedCharacters(),
                    grant.validFrom(),
                    grant.validUntil(),
                    grant.created());
        }
    }

    public record ProviderSpendView(String provider, long reservedMicros, long committedMicros) {
    }

    public record OperatorError(String code) {
    }
}
