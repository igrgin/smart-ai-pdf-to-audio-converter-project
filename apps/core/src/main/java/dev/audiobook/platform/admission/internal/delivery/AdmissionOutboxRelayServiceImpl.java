package dev.audiobook.platform.admission.internal.delivery;

import dev.audiobook.platform.narration.NarrationPlanWorkPublisher;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
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
    private final InspectionWorkPublisher inspectionWorkPublisher;
    private final List<NarrationPlanWorkPublisher> narrationPlanWorkPublishers;
    private final AudiobookConversionService audiobookConversionService;
    private final Clock clock;

    @Override
    public int relayPending() {
        return relay(
                """
                SELECT message_id, work_id
                FROM admission_outbox
                WHERE published_at IS NULL
                ORDER BY created_at, message_id
                LIMIT ?
                """,
                "UPDATE admission_outbox SET published_at = ? WHERE message_id = ? AND published_at IS NULL",
                inspectionWorkPublisher::publish)
                + narrationPlanWorkPublishers.stream()
                        .findFirst()
                        .map(publisher -> audiobookConversionService.relayNarrationPlanWork(publisher::publish))
                        .orElse(0);
    }

    private int relay(String selectionSql, String updateSql, BiConsumer<UUID, UUID> publisher) {
        List<PendingMessage> pending = jdbcTemplate.query(
                selectionSql,
                (resultSet, rowNumber) -> new PendingMessage(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)),
                BATCH_SIZE);
        int published = 0;
        for (PendingMessage message : pending) {
            publisher.accept(message.messageId(), message.workId());
            published += jdbcTemplate.update(
                    updateSql, Timestamp.from(clock.instant()), message.messageId());
        }
        return published;
    }

    private record PendingMessage(UUID messageId, UUID workId) {
    }
}
