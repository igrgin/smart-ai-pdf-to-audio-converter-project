package dev.audiobook.platform.identity.listener;

import dev.audiobook.platform.identity.listener.service.*;

import java.util.UUID;

@FunctionalInterface
public interface ListenerIdGenerator {

    UUID generate();
}
