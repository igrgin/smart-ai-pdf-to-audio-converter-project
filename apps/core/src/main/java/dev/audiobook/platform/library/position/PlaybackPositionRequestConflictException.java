package dev.audiobook.platform.library.position;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class PlaybackPositionRequestConflictException extends RuntimeException {}
