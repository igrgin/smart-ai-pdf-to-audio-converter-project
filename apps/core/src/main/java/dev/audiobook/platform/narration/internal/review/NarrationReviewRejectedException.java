package dev.audiobook.platform.narration.internal.review;

public class NarrationReviewRejectedException extends RuntimeException {

    private final NarrationReviewRejectionReason reason;
    private final Long currentVersion;

    public NarrationReviewRejectedException(NarrationReviewRejectionReason reason) {
        this(reason, null);
    }

    public NarrationReviewRejectedException(NarrationReviewRejectionReason reason, Long currentVersion) {
        super(reason.name());
        this.reason = reason;
        this.currentVersion = currentVersion;
    }

    public NarrationReviewRejectionReason reason() {
        return reason;
    }

    public Long currentVersion() {
        return currentVersion;
    }
}
