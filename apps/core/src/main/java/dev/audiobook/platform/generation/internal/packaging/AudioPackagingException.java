package dev.audiobook.platform.generation.internal.packaging;

public final class AudioPackagingException extends RuntimeException {

    AudioPackagingException(String message) {
        super(message);
    }

    AudioPackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
