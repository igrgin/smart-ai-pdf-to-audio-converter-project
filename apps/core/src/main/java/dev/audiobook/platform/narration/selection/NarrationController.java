package dev.audiobook.platform.narration.selection;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.narration.selection.service.*;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NarrationController {

    private final NarrationSelectionService narrationSelectionService;

    @GetMapping("/narrator-voices")
    NarrationSelectionService.VoiceCatalog catalog() {
        return narrationSelectionService.catalog();
    }

    @PostMapping("/audiobook-conversions/{conversionId}/generation-recipe")
    ResponseEntity<NarrationSelectionService.ConfirmedRecipe> confirm(
            @AuthenticationPrincipal ListenerPrincipal listener,
            @PathVariable UUID conversionId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ConfirmRequest request) {
        if (request.voiceId() == null || request.pace() == null) {
            throw new IllegalArgumentException("voiceId and pace are required");
        }
        long expectedVersion = parseVersion(ifMatch);
        var recipe =
                narrationSelectionService.confirm(
                        new NarrationSelectionService.ConfirmCommand(
                                listener.listenerId(),
                                conversionId,
                                request.voiceId(),
                                request.pace(),
                                expectedVersion,
                                operationKey));
        URI location =
                URI.create("/api/v1/audiobook-conversions/" + conversionId + "/generation-recipe");
        return ResponseEntity.created(location)
                .eTag(Long.toString(recipe.conversionVersion()))
                .body(recipe);
    }

    private static long parseVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) {
            throw new IllegalArgumentException(
                    "If-Match must contain one quoted conversion version");
        }
        return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
    }

    record ConfirmRequest(UUID voiceId, NarrationSelectionService.NarrationPace pace) {}
}
