package dev.audiobook.platform.narration.internal.extraction;

public class DocumentUnderstandingException extends RuntimeException {

    public DocumentUnderstandingException(String message) {
        super(message);
    }

    public DocumentUnderstandingException(String message, Throwable cause) {
        super(message, cause);
    }
}
