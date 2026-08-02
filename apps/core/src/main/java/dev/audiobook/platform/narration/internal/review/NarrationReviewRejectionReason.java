package dev.audiobook.platform.narration.internal.review;

public enum NarrationReviewRejectionReason {
    CONVERSION_UNAVAILABLE,
    REVIEW_NOT_AVAILABLE,
    CONVERSION_VERSION_MISMATCH,
    IDEMPOTENCY_KEY_REUSED,
    INVALID_REVIEW,
    WORKING_ASSET_UNAVAILABLE
}
