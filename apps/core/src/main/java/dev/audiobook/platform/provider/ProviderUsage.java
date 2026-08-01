package dev.audiobook.platform.provider;

public record ProviderUsage(
        String inputMeter,
        long inputUnits,
        String outputMeter,
        long outputUnits) {

    public ProviderUsage {
        if (inputMeter == null || inputMeter.isBlank()
                || outputMeter == null || outputMeter.isBlank()
                || inputUnits < 0 || outputUnits < 0) {
            throw new IllegalArgumentException("Provider usage must be complete and non-negative");
        }
    }
}
