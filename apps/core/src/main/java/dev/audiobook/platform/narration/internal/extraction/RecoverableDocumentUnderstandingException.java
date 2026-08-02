package dev.audiobook.platform.narration.internal.extraction;

public final class RecoverableDocumentUnderstandingException extends DocumentUnderstandingException {

    public RecoverableDocumentUnderstandingException(String message) {
        super(message);
    }

    public RecoverableDocumentUnderstandingException(String message, Throwable cause) {
        super(message, cause);
    }
}
