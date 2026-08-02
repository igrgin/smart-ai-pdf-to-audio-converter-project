package dev.audiobook.platform.worktransport;

import java.util.Map;

public interface WorkTransport {

    void publish(WorkMessage message, String workType);

    record WorkMessage(String payload, Map<String, String> attributes) {
        public WorkMessage {
            attributes = Map.copyOf(attributes);
        }
    }
}
