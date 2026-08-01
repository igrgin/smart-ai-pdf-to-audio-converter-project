package dev.audiobook.platform.admission;

import dev.audiobook.platform.identity.ListenerPrincipal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/publication-submissions")
@RequiredArgsConstructor
@Slf4j
public class PublicationSubmissionController {

    private final PublicationSubmissionService submissionService;
    private final AdmissionOutboxRelayService outboxRelayService;

    @PostMapping
    public ResponseEntity<CreationResponse> create(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateRequest request) {
        PublicationSubmissionService.Creation creation = submissionService.create(
                new PublicationSubmissionService.CreateCommand(
                        principal.listenerId(),
                        request.mediaType(),
                        request.byteLength(),
                        request.sha256(),
                        request.rightsAttestation().termsVersion(),
                        request.rightsAttestation().noticeVersion(),
                        idempotencyKey));
        String resource = "/api/v1/publication-submissions/" + creation.submissionId();
        PublicationSubmissionService.UploadSession session = creation.uploadSession();
        CreationResponse body = new CreationResponse(
                creation.submissionId(),
                creation.state(),
                new UploadSessionResponse(
                        resource + "/upload",
                        session.token(),
                        session.expiresAt(),
                        session.chunkSize()),
                creation.created());
        return ResponseEntity.accepted()
                .location(URI.create(resource))
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    @PutMapping(path = "/{submissionId}/upload", consumes = "application/octet-stream")
    public ResponseEntity<PublicationSubmissionService.UploadProgress> upload(
            @PathVariable UUID submissionId,
            @RequestHeader("Upload-Token") String token,
            @RequestHeader("Upload-Offset") long offset,
            @RequestHeader("Upload-Length") long totalBytes,
            @RequestHeader("Upload-Chunk-SHA256") String chunkSha256,
            @RequestBody byte[] bytes) {
        PublicationSubmissionService.UploadProgress progress = submissionService.upload(
                new PublicationSubmissionService.UploadCommand(
                        submissionId,
                        token,
                        offset,
                        totalBytes,
                        chunkSha256,
                        bytes));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(progress);
    }

    @PostMapping("/{submissionId}/confirm")
    public ResponseEntity<PublicationSubmissionService.Submission> confirm(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID submissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ConfirmRequest request) {
        PublicationSubmissionService.Submission submission = submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        principal.listenerId(),
                        submissionId,
                        request.storageGeneration(),
                        request.byteLength(),
                        request.sha256(),
                        idempotencyKey));
        try {
            outboxRelayService.relayPending();
        } catch (RuntimeException exception) {
            log.warn("admission_outbox_relay_deferred");
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(submission);
    }

    @GetMapping("/{submissionId}")
    public ResponseEntity<PublicationSubmissionService.Submission> submission(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID submissionId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(submissionService.submission(principal.listenerId(), submissionId));
    }

    @DeleteMapping("/{submissionId}")
    public ResponseEntity<PublicationSubmissionService.Submission> cancel(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID submissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(submissionService.cancel(new PublicationSubmissionService.CancelCommand(
                        principal.listenerId(), submissionId, idempotencyKey)));
    }

    public record ConfirmRequest(String storageGeneration, long byteLength, String sha256) {
    }

    public record CreateRequest(
            String mediaType,
            long byteLength,
            String sha256,
            RightsAttestationRequest rightsAttestation) {
    }

    public record RightsAttestationRequest(String termsVersion, String noticeVersion) {
    }

    public record CreationResponse(
            UUID submissionId,
            PublicationSubmissionService.SubmissionState state,
            UploadSessionResponse uploadSession,
            boolean created) {
    }

    public record UploadSessionResponse(
            String endpoint,
            String token,
            Instant expiresAt,
            int chunkSize) {
    }
}
