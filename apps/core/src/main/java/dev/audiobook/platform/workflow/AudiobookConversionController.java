package dev.audiobook.platform.workflow;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.narration.NarrationPlanService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audiobook-conversions")
@RequiredArgsConstructor
public class AudiobookConversionController {

    private final AudiobookConversionService conversionService;
    private final NarrationPlanService narrationPlanService;

    @GetMapping("/{conversionId}")
    public ResponseEntity<ConversionProgress> conversion(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID conversionId,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        AudiobookConversionService.AudiobookConversion conversion =
                conversionService.conversion(principal.listenerId(), conversionId);
        String entityTag = "\"" + conversion.version() + "\"";
        if (matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .cacheControl(CacheControl.noStore())
                    .eTag(entityTag)
                    .build();
        }
        NarrationPlanService.PlanView plan = conversion.state()
                                == AudiobookConversionService.ConversionState.AWAITING_REVIEW
                        && "NARRATION_REVIEW_AVAILABLE".equals(conversion.reasonCode())
                ? narrationPlanService.plan(principal.listenerId(), conversionId)
                : null;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(entityTag)
                .body(new ConversionProgress(
                        conversion.conversionId(),
                        conversion.state(),
                        conversion.reasonCode(),
                        conversion.allowedActions(),
                        conversion.version(),
                        plan));
    }

    private static boolean matches(String ifNoneMatch, String entityTag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String normalized = candidate.strip();
            if (normalized.equals("*") || normalized.equals(entityTag) || normalized.equals("W/" + entityTag)) {
                return true;
            }
        }
        return false;
    }

    public record ConversionProgress(
            UUID conversionId,
            AudiobookConversionService.ConversionState state,
            String reasonCode,
            java.util.List<AudiobookConversionService.AllowedAction> allowedActions,
            long version,
            NarrationPlanService.PlanView narrationPlan) {
    }
}
