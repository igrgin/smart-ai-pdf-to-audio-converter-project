package dev.audiobook.platform.identity.internal.session;

import java.util.UUID;

@FunctionalInterface
public interface ListenerIdGenerator {

    UUID generate();
}
