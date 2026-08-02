package dev.audiobook.platform.admission.submission.dto;

import java.time.Instant;

public record UploadSessionResponse(
        String endpoint, String token, Instant expiresAt, int chunkSize) {}
