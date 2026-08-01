package dev.audiobook.platform.offline.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
final class OfflineAuthorizationConflictException extends RuntimeException {
}
