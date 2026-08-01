package dev.audiobook.platform.narration;

public class SourceTooDamagedException extends RuntimeException {

    public static final String REASON_CODE = "SOURCE_TOO_DAMAGED";
    public static final String LISTENER_GUIDANCE =
            "This PDF has too many unreadable pages. Retry only if extraction conditions improved; "
                    + "otherwise start a new conversion with a clearer copy.";

    private final int resumeFromPage;

    public SourceTooDamagedException(int resumeFromPage) {
        super(REASON_CODE);
        this.resumeFromPage = resumeFromPage;
    }

    public int resumeFromPage() {
        return resumeFromPage;
    }

    public String reasonCode() {
        return REASON_CODE;
    }

    public String listenerGuidance() {
        return LISTENER_GUIDANCE;
    }
}
