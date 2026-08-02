package dev.audiobook.platform.identifier.internal;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UuidV7PlatformIdentifierGeneratorImpl implements PlatformIdentifierGenerator {

    private static final long RAND_B_MASK = 0x3fff_ffff_ffff_ffffL;

    private final Clock identityClock;
    private final SecureRandom identitySecureRandom;

    @Override
    public UUID generate() {
        long unixMillis = identityClock.millis() & 0x0000_ffff_ffff_ffffL;
        long mostSignificantBits = (unixMillis << 16) | 0x7000L | identitySecureRandom.nextInt(1 << 12);
        long leastSignificantBits = (identitySecureRandom.nextLong() & RAND_B_MASK) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }
}
