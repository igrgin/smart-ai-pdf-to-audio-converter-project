package dev.audiobook.platform.narration.selection.error.exception;

import dev.audiobook.platform.narration.NarrationRejectionReason;

public class NarrationSelectionRejectedException extends RuntimeException {

    private final NarrationRejectionReason reason;

    public NarrationSelectionRejectedException(NarrationRejectionReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public NarrationRejectionReason reason() {
        return reason;
    }
}
