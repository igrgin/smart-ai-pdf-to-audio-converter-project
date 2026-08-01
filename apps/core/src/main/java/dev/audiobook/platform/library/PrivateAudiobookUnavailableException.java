package dev.audiobook.platform.library;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
final class PrivateAudiobookUnavailableException extends RuntimeException {
}
