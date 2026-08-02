package dev.audiobook.platform.generation.speech.validation;

import dev.audiobook.platform.generation.speech.validation.service.*;

public final class SpeechValidationException extends RuntimeException {

    private final Code code;

    public SpeechValidationException(Code code) {
        super("Speech result failed canonical validation");
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        PROVIDER_DRIFT,
        INVALID_PCM
    }
}
