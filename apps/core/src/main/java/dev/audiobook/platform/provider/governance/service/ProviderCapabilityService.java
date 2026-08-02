package dev.audiobook.platform.provider.governance.service;

import dev.audiobook.platform.provider.governance.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface ProviderCapabilityService {

    CapabilityProfile qualified(String profileVersion, ServiceKind service, InputKind input);

    enum ServiceKind {
        ANALYSIS,
        SPEECH
    }

    enum InputKind {
        CANONICAL_TEXT,
        CANONICAL_PAGE_IMAGE
    }

    record CapabilityProfile(
            UUID profileId,
            String profileVersion,
            String provider,
            ServiceKind service,
            String endpoint,
            String modelSnapshot,
            String deliveryMode,
            String region,
            String dataPolicyVersion,
            Set<InputKind> supportedInputs,
            long maximumInputUnits,
            String inputUnit,
            String quotaMeter,
            long quotaLimit,
            int quotaWindowSeconds,
            String priceMeter,
            String requestFormat,
            String responseFormat,
            String nativeControls,
            String nativeControlsSchema,
            Instant validFrom,
            Instant validUntil) {

        public CapabilityProfile {
            supportedInputs = Set.copyOf(supportedInputs);
        }
    }
}
