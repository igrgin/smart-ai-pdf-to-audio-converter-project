package dev.audiobook.platform.provider;

public final class ProviderAnalysisException extends RuntimeException {

    private final Code code;

    ProviderAnalysisException(Code code) {
        super("Governed provider analysis failed");
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_COMMAND,
        INPUT_LIMIT_EXCEEDED,
        ADAPTER_UNAVAILABLE,
        CONFIGURATION_UNAVAILABLE,
        RATE_LIMITED,
        PROVIDER_UNAVAILABLE,
        INVALID_RESPONSE,
        PROVIDER_DRIFT,
        INVALID_SCHEMA_OUTCOME
    }
}
