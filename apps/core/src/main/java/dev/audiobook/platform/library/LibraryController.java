package dev.audiobook.platform.library;

import dev.audiobook.platform.entitlement.ledger.service.ConversionEntitlementService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.library.service.*;
import dev.audiobook.platform.library.service.PrivateAudiobookLibraryService;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/library")
@RequiredArgsConstructor
public class LibraryController {

    private final ConversionEntitlementService entitlementService;
    private final AudiobookConversionService audiobookConversionService;
    private final NarrationSelectionService narrationSelectionService;
    private final PrivateAudiobookLibraryService privateAudiobookLibraryService;

    @GetMapping
    public ResponseEntity<LibraryView> library(
            @AuthenticationPrincipal ListenerPrincipal principal) {
        List<String> methods =
                principal.providers().stream()
                        .map(provider -> provider.name().toLowerCase(Locale.ROOT))
                        .sorted()
                        .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new LibraryView(
                                principal.displayName(),
                                principal.contactEmail(),
                                methods,
                                audiobookConversionService
                                        .conversions(principal.listenerId())
                                        .stream()
                                        .map(
                                                conversion ->
                                                        AudiobookView.from(
                                                                conversion,
                                                                narrationSelectionService
                                                                        .narrationChoice(
                                                                                principal
                                                                                        .listenerId(),
                                                                                conversion
                                                                                        .conversionId()),
                                                                conversion.state()
                                                                                == AudiobookConversionService
                                                                                        .ConversionState
                                                                                        .FINALIZED
                                                                        ? privateAudiobookLibraryService
                                                                                .find(
                                                                                        principal
                                                                                                .listenerId(),
                                                                                        conversion
                                                                                                .conversionId())
                                                                        : null))
                                        .toList(),
                                ConversionEntitlementView.from(
                                        entitlementService.allowance(principal.listenerId()))));
    }

    public record LibraryView(
            String displayName,
            String contactEmail,
            List<String> signInMethods,
            List<AudiobookView> audiobooks,
            ConversionEntitlementView conversionEntitlement) {}

    public record AudiobookView(
            java.util.UUID conversionId,
            AudiobookConversionService.ConversionState state,
            String reasonCode,
            List<AudiobookConversionService.AllowedAction> allowedActions,
            long version,
            java.util.UUID recipeId,
            java.util.UUID voiceId,
            String voiceDisplayName,
            NarrationSelectionService.NarrationPace pace,
            boolean explicitNarrationChoiceRequired,
            PrivateAudiobookView privateAudiobook) {

        static AudiobookView from(
                AudiobookConversionService.AudiobookConversion conversion,
                NarrationSelectionService.NarrationChoiceStatus choice,
                PrivateAudiobookLibraryService.PrivateAudiobook privateAudiobook) {
            return new AudiobookView(
                    conversion.conversionId(),
                    conversion.state(),
                    conversion.reasonCode(),
                    conversion.allowedActions(),
                    choice.conversionVersion(),
                    choice.recipeId(),
                    choice.voiceId(),
                    choice.voiceDisplayName(),
                    choice.pace(),
                    choice.explicitChoiceRequired(),
                    PrivateAudiobookView.from(privateAudiobook));
        }
    }

    public record PrivateAudiobookView(
            java.util.UUID audiobookId,
            java.util.UUID assetVersionId,
            String availability,
            long totalDurationMs,
            String manifestUrl) {

        static PrivateAudiobookView from(
                PrivateAudiobookLibraryService.PrivateAudiobook audiobook) {
            if (audiobook == null) {
                return null;
            }
            return new PrivateAudiobookView(
                    audiobook.audiobookId(),
                    audiobook.assetVersionId(),
                    audiobook.availability(),
                    audiobook.totalDurationMs(),
                    "/api/v1/audiobooks/"
                            + audiobook.audiobookId()
                            + "/asset-versions/"
                            + audiobook.assetVersionId()
                            + "/manifest");
        }
    }

    public record ConversionEntitlementView(
            ConversionEntitlementService.AllowanceStatus status,
            long grantedCharacters,
            long availableCharacters,
            long reservedCharacters,
            long committedCharacters,
            boolean canStartConversion,
            String denialReason,
            ConversionEntitlementService.EntitlementSource source,
            ConversionEntitlementService.DemonstrationSubscriptionStatus
                    demonstrationSubscriptionStatus,
            boolean demonstrationOnly) {

        static ConversionEntitlementView from(ConversionEntitlementService.Allowance allowance) {
            return new ConversionEntitlementView(
                    allowance.status(),
                    allowance.grantedCharacters(),
                    allowance.availableCharacters(),
                    allowance.reservedCharacters(),
                    allowance.committedCharacters(),
                    allowance.status() == ConversionEntitlementService.AllowanceStatus.AVAILABLE,
                    allowance.denialReason(),
                    allowance.source(),
                    allowance.demonstrationSubscriptionStatus(),
                    allowance.source()
                            == ConversionEntitlementService.EntitlementSource
                                    .DEMONSTRATION_SUBSCRIPTION);
        }
    }
}
