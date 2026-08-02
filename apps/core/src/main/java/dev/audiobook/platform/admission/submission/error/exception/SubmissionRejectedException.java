package dev.audiobook.platform.admission.submission.error.exception;

public class SubmissionRejectedException extends RuntimeException {

    private final String reasonCode;

    public SubmissionRejectedException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
