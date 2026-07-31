package dev.audiobook.platform.identity;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class UuidV7ListenerIdGenerator implements ListenerIdGenerator {

    private static final long RAND_B_MASK = 0x3fff_ffff_ffff_ffffL;

    private final Clock clock;
    private final SecureRandom random;

    UuidV7ListenerIdGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UuidV7ListenerIdGenerator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    @Override
    public UUID generate() {
        long unixMillis = clock.millis() & 0x0000_ffff_ffff_ffffL;
        long mostSignificantBits = (unixMillis << 16) | 0x7000L | random.nextInt(1 << 12);
        long leastSignificantBits = (random.nextLong() & RAND_B_MASK) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
