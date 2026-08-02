package dev.audiobook.platform.generation.packaging;

import dev.audiobook.platform.generation.packaging.service.*;

public final class AudioPackagingException extends RuntimeException {

    public AudioPackagingException(String message) {
        super(message);
    }

    public AudioPackagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
