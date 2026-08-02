package dev.audiobook.platform.retention.deletion;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.retention.deletion.service.DeletionRequestService;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class DeletionController {

    private final DeletionRequestService deletionRequestService;

    @DeleteMapping("/api/v1/audiobooks/{audiobookId}")
    public ResponseEntity<DeletionRequest.DeletionReceipt> deleteAudiobook(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID audiobookId,
            @RequestHeader("Idempotency-Key") String operationKey,
            @RequestHeader("If-Match") String ifMatch) {
        DeletionRequest.DeletionReceipt receipt =
                deletionRequestService.deleteAudiobook(
                        new DeletionRequest.DeleteAudiobookCommand(
                                principal.listenerId(),
                                audiobookId,
                                expectedVersion(ifMatch),
                                operationKey));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/deletions/" + receipt.requestId()))
                .cacheControl(CacheControl.noStore())
                .body(receipt);
    }

    @DeleteMapping("/api/v1/account")
    public ResponseEntity<DeletionRequest.DeletionReceipt> deleteAccount(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @RequestHeader("Idempotency-Key") String operationKey,
            HttpServletRequest request) {
        DeletionRequest.DeletionReceipt receipt =
                deletionRequestService.deleteAccount(
                        new DeletionRequest.DeleteAccountCommand(
                                principal.listenerId(), operationKey));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/deletions/" + receipt.requestId()))
                .cacheControl(CacheControl.noStore())
                .body(receipt);
    }

    @GetMapping("/api/v1/deletions/{requestId}")
    public ResponseEntity<DeletionRequest.DeletionStatus> status(
            @AuthenticationPrincipal ListenerPrincipal principal, @PathVariable UUID requestId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(deletionRequestService.status(principal.listenerId(), requestId));
    }

    private static long expectedVersion(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() < 3
                || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException("If-Match must contain the quoted audiobook version");
        }
        try {
            long parsed = Long.parseLong(value.substring(1, value.length() - 1));
            if (parsed < 0) {
                throw new IllegalArgumentException("Audiobook version must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match contains an invalid audiobook version", exception);
        }
    }
}
