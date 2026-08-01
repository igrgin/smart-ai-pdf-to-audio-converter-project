package dev.audiobook.platform.admission;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface QuarantineObjectStore {

    StoredObject append(UUID objectId, long expectedOffset, byte[] bytes, boolean complete) throws IOException;

    StoredObject inspect(UUID objectId) throws IOException;

    InputStream read(UUID objectId) throws IOException;

    void delete(UUID objectId) throws IOException;

    record StoredObject(String key, String generation, long byteLength, String sha256) {
    }
}
