package dev.audiobook.platform.provider;

public final class ProviderCapabilityRejectedException extends RuntimeException {

    private final Code code;

    ProviderCapabilityRejectedException(Code code) {
        super("Provider capability routing was rejected");
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        PROFILE_UNAVAILABLE,
        SERVICE_UNSUPPORTED,
        INPUT_UNSUPPORTED,
        PROFILE_STALE,
        PRIVACY_STALE,
        REGION_STALE,
        ACCESS_STALE,
        QUOTA_STALE,
        EVALUATION_STALE
    }
}
