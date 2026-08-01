package dev.audiobook.platform.admission;

import java.io.InputStream;

public interface EpubInspectionService {

    Result inspect(InputStream publication);

    record Result(boolean accepted, String reasonCode) {
        public static Result admissionAllowed() {
            return new Result(true, null);
        }

        public static Result rejected(String reasonCode) {
            return new Result(false, reasonCode);
        }
    }
}
