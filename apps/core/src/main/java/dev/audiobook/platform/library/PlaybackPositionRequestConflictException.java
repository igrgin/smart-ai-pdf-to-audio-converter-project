package dev.audiobook.platform.library;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
final class PlaybackPositionRequestConflictException extends RuntimeException {
}
