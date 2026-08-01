package dev.audiobook.platform.identity;

import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/library")
@RequiredArgsConstructor
public class LibraryController {

    private final ConversionEntitlementService entitlementService;
    private final AudiobookConversionService audiobookConversionService;

    @GetMapping
    public ResponseEntity<LibraryView> library(@AuthenticationPrincipal ListenerPrincipal principal) {
        List<String> methods = principal.providers().stream()
                .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                .sorted()
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LibraryView(
                        principal.displayName(),
                        principal.contactEmail(),
                        methods,
                        audiobookConversionService.conversions(principal.listenerId()),
                        ConversionEntitlementView.from(entitlementService.allowance(principal.listenerId()))));
    }

    public record LibraryView(
            String displayName,
            String contactEmail,
            List<String> signInMethods,
            List<AudiobookConversionService.AudiobookConversion> audiobooks,
            ConversionEntitlementView conversionEntitlement) {
    }

    public record ConversionEntitlementView(
            ConversionEntitlementService.AllowanceStatus status,
            long grantedCharacters,
            long availableCharacters,
            long reservedCharacters,
            long committedCharacters,
            boolean canStartConversion,
            String denialReason) {

        static ConversionEntitlementView from(ConversionEntitlementService.Allowance allowance) {
            return new ConversionEntitlementView(
                    allowance.status(),
                    allowance.grantedCharacters(),
                    allowance.availableCharacters(),
                    allowance.reservedCharacters(),
                    allowance.committedCharacters(),
                    allowance.status() == ConversionEntitlementService.AllowanceStatus.AVAILABLE,
                    allowance.denialReason());
        }
    }
}
