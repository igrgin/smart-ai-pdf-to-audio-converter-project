package dev.audiobook.platform.retention.restore;

import dev.audiobook.platform.retention.RetentionProperties;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RestoreSafetyGate {

    private final AtomicBoolean safe;

    public RestoreSafetyGate(RetentionProperties properties) {
        safe = new AtomicBoolean(!properties.restoreReplayEnabled());
    }

    public boolean isSafe() {
        return safe.get();
    }

    void markSafe() {
        safe.set(true);
    }
}
