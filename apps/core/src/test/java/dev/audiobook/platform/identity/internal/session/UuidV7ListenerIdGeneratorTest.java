package dev.audiobook.platform.identity.internal.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7ListenerIdGeneratorTest {

    @Test
    void generatesTimeOrderedRfc9562ListenerIdentifiers() {
        long millis = Instant.parse("2026-07-31T20:00:00Z").toEpochMilli();
        UuidV7ListenerIdGenerator generator = new UuidV7ListenerIdGenerator(
                Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), new SecureRandom());

        UUID listenerId = generator.generate();

        assertThat(listenerId.version()).isEqualTo(7);
        assertThat(listenerId.variant()).isEqualTo(2);
        assertThat(listenerId.getMostSignificantBits() >>> 16).isEqualTo(millis);
    }
}
