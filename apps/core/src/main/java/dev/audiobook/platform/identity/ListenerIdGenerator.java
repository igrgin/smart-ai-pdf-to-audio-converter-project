package dev.audiobook.platform.identity;

import java.util.UUID;

@FunctionalInterface
interface ListenerIdGenerator {

    UUID generate();
}
