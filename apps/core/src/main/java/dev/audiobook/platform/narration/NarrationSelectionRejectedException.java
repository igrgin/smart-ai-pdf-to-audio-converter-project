package dev.audiobook.platform.narration;

public class NarrationSelectionRejectedException extends RuntimeException {

    private final String reasonCode;

    NarrationSelectionRejectedException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
