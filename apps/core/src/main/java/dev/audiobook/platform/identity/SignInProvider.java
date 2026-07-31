package dev.audiobook.platform.identity;

public enum SignInProvider {
    GOOGLE,
    APPLE,
    FACEBOOK;

    public static SignInProvider fromRegistrationId(String registrationId) {
        return valueOf(registrationId.toUpperCase(java.util.Locale.ROOT));
    }
}
