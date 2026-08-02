package dev.audiobook.platform.offline.internal.authorization;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OfflineAccessPropertiesTest {

    @Test
    void rejectsAuthorizationLifetimeBeyondThirtyDays() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OfflineAccessProperties(
                Duration.ofDays(31), 4, null, null, null));
    }

    @Test
    void rejectsNonPositiveChunkSize() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OfflineAccessProperties(
                Duration.ofDays(30), 0, null, null, null));
    }
}
