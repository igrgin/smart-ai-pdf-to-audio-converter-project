package dev.audiobook.platform.status;

public record PlatformStatus(String apiVersion, Build build, AvailabilityState availability) {

    public PlatformStatus(
            String apiVersion,
            String buildVersion,
            String buildRevision,
            Availability coreAvailability,
            Availability databaseAvailability) {
        this(
                apiVersion,
                new Build(buildVersion, buildRevision),
                new AvailabilityState(coreAvailability, databaseAvailability));
    }

    public record Build(String version, String revision) {
    }

    public record AvailabilityState(Availability core, Availability database) {
    }

    public enum Availability {
        AVAILABLE,
        DEGRADED
    }
}
