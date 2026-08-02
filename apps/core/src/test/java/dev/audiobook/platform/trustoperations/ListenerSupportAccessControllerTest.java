package dev.audiobook.platform.trustoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ListenerSupportAccessControllerTest {

    @Test
    void parsesOnlyQuotedNonNegativeGrantVersions() {
        assertThat(ListenerSupportAccessController.expectedVersion("\"0\"")).isZero();
        assertThat(ListenerSupportAccessController.expectedVersion("\"42\"")).isEqualTo(42);
        assertThatThrownBy(() -> ListenerSupportAccessController.expectedVersion("42"))
                .isInstanceOf(TrustOperationsPreconditionException.class);
        assertThatThrownBy(() -> ListenerSupportAccessController.expectedVersion("\"-1\""))
                .isInstanceOf(TrustOperationsPreconditionException.class);
        assertThatThrownBy(() -> ListenerSupportAccessController.expectedVersion(null))
                .isInstanceOf(TrustOperationsPreconditionException.class);
    }
}
