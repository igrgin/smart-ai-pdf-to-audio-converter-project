package dev.audiobook.platform.identity.internal.listener;

import java.util.UUID;

@FunctionalInterface
public interface ListenerIdGenerator {

    UUID generate();
}
