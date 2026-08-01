package dev.audiobook.platform.generation;

public final class SpeechProviderException extends RuntimeException {

    private final Code code;
    private final boolean retryable;

    SpeechProviderException(Code code, boolean retryable, Throwable cause) {
        super("Approved speech provider request failed", cause);
        this.code = code;
        this.retryable = retryable;
    }

    SpeechProviderException(Code code, boolean retryable) {
        this(code, retryable, null);
    }

    public Code code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public enum Code {
        CONFIGURATION_UNAVAILABLE,
        INVALID_REQUEST,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        AMBIGUOUS_TIMEOUT,
        INVALID_RESPONSE
    }
}
