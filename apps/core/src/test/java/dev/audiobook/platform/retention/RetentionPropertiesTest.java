package dev.audiobook.platform.retention;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class RetentionPropertiesTest {

    @Test
    void acceptsTheContractDeadlines() {
        assertThatCode(
                        () ->
                                new RetentionProperties(
                                        "retention-test-key-with-32-characters",
                                        java.nio.file.Path.of("retention-test"),
                                        "retention-test-bucket",
                                        Duration.ofHours(24),
                                        Duration.ofDays(23),
                                        Duration.ofDays(30),
                                        Duration.ofDays(90),
                                        Duration.ofDays(365),
                                        100,
                                        5,
                                        true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDeadlinesBeyondTheErasureContract() {
        assertThatThrownBy(
                        () ->
                                new RetentionProperties(
                                        "retention-test-key-with-32-characters",
                                        java.nio.file.Path.of("retention-test"),
                                        "retention-test-bucket",
                                        Duration.ofHours(25),
                                        Duration.ofDays(23),
                                        Duration.ofDays(30),
                                        Duration.ofDays(90),
                                        Duration.ofDays(365),
                                        100,
                                        5,
                                        true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quick erasure target");
    }
}
