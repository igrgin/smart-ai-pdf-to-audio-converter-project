package dev.audiobook.platform.admission;

import java.io.InputStream;

public interface PublicationInspectionService {

    Result inspect(InputStream publication, String declaredMediaType);

    record Result(boolean accepted, String reasonCode, String mediaType, String toolchainVersion) {
        public Result {
            boolean admittedShape = accepted
                    && reasonCode == null
                    && mediaType != null
                    && !mediaType.isBlank()
                    && toolchainVersion != null
                    && !toolchainVersion.isBlank();
            boolean rejectedShape = !accepted
                    && reasonCode != null
                    && !reasonCode.isBlank()
                    && mediaType == null
                    && toolchainVersion == null;
            if (!admittedShape && !rejectedShape) {
                throw new IllegalArgumentException("Inspection result shape does not match its decision");
            }
        }

        public static Result admissionAllowed(String mediaType, String toolchainVersion) {
            return new Result(true, null, mediaType, toolchainVersion);
        }

        public static Result rejected(String reasonCode) {
            return new Result(false, reasonCode, null, null);
        }
    }
}
