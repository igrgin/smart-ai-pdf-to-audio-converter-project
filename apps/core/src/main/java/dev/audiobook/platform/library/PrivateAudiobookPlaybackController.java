package dev.audiobook.platform.library;

import dev.audiobook.platform.identity.ListenerPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audiobooks")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class PrivateAudiobookPlaybackController {

    private final PrivateAudiobookLibraryService libraryService;

    @GetMapping("/{audiobookId}/asset-versions/{assetVersionId}/manifest")
    public ResponseEntity<PrivateAudiobookLibraryService.PlaybackManifest> manifest(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID audiobookId,
            @PathVariable UUID assetVersionId) {
        PrivateAudiobookLibraryService.PlaybackManifest manifest =
                libraryService.manifest(principal.listenerId(), audiobookId, assetVersionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(quoted("sha256:" + manifest.manifestDigest()))
                .body(manifest);
    }

    @RequestMapping(
            path = "/{audiobookId}/asset-versions/{assetVersionId}/parts/{partId}/media",
            method = {RequestMethod.GET, RequestMethod.HEAD})
    public ResponseEntity<byte[]> media(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID audiobookId,
            @PathVariable UUID assetVersionId,
            @PathVariable UUID partId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            @RequestHeader(value = HttpHeaders.IF_RANGE, required = false) String ifRange,
            HttpServletRequest request) {
        PrivateAudiobookLibraryService.MediaResponse media = libraryService.media(
                principal.listenerId(),
                audiobookId,
                assetVersionId,
                partId,
                range,
                ifRange,
                RequestMethod.HEAD.name().equals(request.getMethod()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                        media.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(media.mimeType()))
                .eTag(quoted(media.entityTag()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentLength(media.partial()
                        ? media.rangeEnd() - media.rangeStart() + 1
                        : media.totalLength());
        if (media.partial()) {
            response.header(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes " + media.rangeStart() + '-' + media.rangeEnd() + '/' + media.totalLength());
        }
        return response.body(media.content());
    }

    @PutMapping("/{audiobookId}/asset-versions/{assetVersionId}/playback-position")
    public ResponseEntity<PrivateAudiobookLibraryService.ResumePosition> updatePosition(
            @AuthenticationPrincipal ListenerPrincipal principal,
            @PathVariable UUID audiobookId,
            @PathVariable UUID assetVersionId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PositionRequest request) {
        PrivateAudiobookLibraryService.ResumePosition position = libraryService.updatePosition(
                new PrivateAudiobookLibraryService.UpdatePosition(
                        principal.listenerId(),
                        audiobookId,
                        assetVersionId,
                        request.positionMs(),
                        expectedVersion(ifMatch),
                        idempotencyKey));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(position.version()))
                .body(position);
    }

    @ExceptionHandler(UnsatisfiedRangeException.class)
    ResponseEntity<Void> unsatisfiedRange(UnsatisfiedRangeException exception) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.completeLength())
                .build();
    }

    private static String quoted(String entityTag) {
        return '"' + entityTag + '"';
    }

    private static long expectedVersion(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[0-9]+\"")) {
            throw new PlaybackPositionConflictException();
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new PlaybackPositionConflictException();
        }
    }

    public record PositionRequest(long positionMs) {
    }
}
