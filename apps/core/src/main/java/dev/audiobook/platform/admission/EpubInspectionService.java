package dev.audiobook.platform.admission;

import java.nio.file.Path;

public interface EpubInspectionService {

    Result inspect(Path publication);

    record Result(boolean accepted, String reasonCode) {
        public static Result admissionAllowed() {
            return new Result(true, null);
        }

        public static Result rejected(String reasonCode) {
            return new Result(false, reasonCode);
        }
    }
}
