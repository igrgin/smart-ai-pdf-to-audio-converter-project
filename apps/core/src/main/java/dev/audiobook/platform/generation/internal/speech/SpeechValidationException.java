package dev.audiobook.platform.generation.internal.speech;

public final class SpeechValidationException extends RuntimeException {

    private final Code code;

    SpeechValidationException(Code code) {
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
