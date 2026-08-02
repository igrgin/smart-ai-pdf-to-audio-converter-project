package dev.audiobook.platform.trustoperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class TrustOperationsPropertiesTest {

    @Test
    void requiresEverySecurityDurationToBePositive() {
        assertThatThrownBy(
                        () ->
                                new TrustOperationsProperties(
                                        Duration.ZERO,
                                        Duration.ofMinutes(30),
                                        Duration.ofHours(24),
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TrustOperationsProperties(
                                        Duration.ofHours(24),
                                        null,
                                        Duration.ofHours(24),
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TrustOperationsProperties(
                                        Duration.ofHours(24),
                                        Duration.ofMinutes(30),
                                        Duration.ofHours(-1),
                                        Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TrustOperationsProperties(
                                        Duration.ofHours(24),
                                        Duration.ofMinutes(30),
                                        Duration.ofHours(24),
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
