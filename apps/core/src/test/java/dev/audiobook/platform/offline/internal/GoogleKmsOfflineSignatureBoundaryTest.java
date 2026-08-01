package dev.audiobook.platform.offline.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GoogleKmsOfflineSignatureBoundaryTest {

    @Test
    void convertsCloudKmsDerEcdsaSignatureToWebCryptoP1363() {
        byte[] der = new byte[] {0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02};

        byte[] converted = GoogleKmsOfflineSignatureBoundary.derToP1363(der);

        assertThat(converted).hasSize(64);
        assertThat(converted[31]).isEqualTo((byte) 1);
        assertThat(converted[63]).isEqualTo((byte) 2);
        assertThatThrownBy(() -> GoogleKmsOfflineSignatureBoundary.derToP1363(new byte[] {0x30, 0x01}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
