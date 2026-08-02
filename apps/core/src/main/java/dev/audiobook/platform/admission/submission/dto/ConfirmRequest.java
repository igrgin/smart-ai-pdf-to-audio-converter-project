package dev.audiobook.platform.admission.submission.dto;

public record ConfirmRequest(String storageGeneration, long byteLength, String sha256) {}
