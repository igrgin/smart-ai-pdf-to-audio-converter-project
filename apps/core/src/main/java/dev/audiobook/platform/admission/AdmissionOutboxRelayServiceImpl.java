package dev.audiobook.platform.admission;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mode", havingValue = "core", matchIfMissing = true)
public class AdmissionOutboxRelayServiceImpl implements AdmissionOutboxRelayService {

    private static final int BATCH_SIZE = 20;

    private final JdbcTemplate jdbcTemplate;
    private final InspectionWorkPublisher publisher;
    private final Clock clock;

    @Override
    public int relayPending() {
        List<PendingMessage> pending = jdbcTemplate.query(
                """
                SELECT message_id, work_id
                FROM admission_outbox
                WHERE published_at IS NULL
                ORDER BY created_at, message_id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new PendingMessage(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)),
                BATCH_SIZE);

        int published = 0;
        for (PendingMessage message : pending) {
            publisher.publish(message.messageId(), message.workId());
            published += jdbcTemplate.update(
                    "UPDATE admission_outbox SET published_at = ? WHERE message_id = ? AND published_at IS NULL",
                    Timestamp.from(clock.instant()),
                    message.messageId());
        }
        return published;
    }

    private record PendingMessage(UUID messageId, UUID workId) {
    }
}
