package dev.audiobook.platform.library;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
final class PlaybackPositionConflictException extends RuntimeException {
}
