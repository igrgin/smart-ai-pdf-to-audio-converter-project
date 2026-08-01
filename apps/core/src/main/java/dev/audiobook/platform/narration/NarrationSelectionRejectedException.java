package dev.audiobook.platform.narration;

public class NarrationSelectionRejectedException extends RuntimeException {

    private final NarrationRejectionReason reason;

    NarrationSelectionRejectedException(NarrationRejectionReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public NarrationRejectionReason reason() {
        return reason;
    }
}
