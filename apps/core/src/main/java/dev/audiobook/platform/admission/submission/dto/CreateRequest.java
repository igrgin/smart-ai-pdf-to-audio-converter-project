package dev.audiobook.platform.admission.submission.dto;

public record CreateRequest(
        String mediaType,
        long byteLength,
        String sha256,
        RightsAttestationRequest rightsAttestation) {}
