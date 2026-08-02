package dev.audiobook.platform.library.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public final class PlaybackPositionConflictException extends RuntimeException {
}
