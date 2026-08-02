package dev.audiobook.platform.admission.internal.inspection;

import java.nio.file.Path;

public interface QpdfValidationService {

    Result validate(Path publication);

    enum Result {
        VALID,
        VALID_WITH_WARNINGS,
        ENCRYPTED,
        INVALID,
        FAILED,
        TIMED_OUT
    }
}
