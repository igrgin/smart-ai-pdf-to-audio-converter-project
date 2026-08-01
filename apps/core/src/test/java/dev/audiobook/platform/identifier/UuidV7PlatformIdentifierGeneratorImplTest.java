package dev.audiobook.platform.identifier;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UuidV7PlatformIdentifierGeneratorImplTest {

    @Test
    void createsRfcVariantUuidV7WithTheCurrentUnixMillisecondPrefix() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        PlatformIdentifierGenerator generator = new UuidV7PlatformIdentifierGeneratorImpl(
                Clock.fixed(now, ZoneOffset.UTC), new SecureRandom(new byte[] {2, 3}));

        var identifier = generator.generate();

        assertThat(identifier.version()).isEqualTo(7);
        assertThat(identifier.variant()).isEqualTo(2);
        assertThat(identifier.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }
}
