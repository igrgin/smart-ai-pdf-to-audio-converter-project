package dev.audiobook.platform.workflow;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InspectionWorkflowServiceImpl implements InspectionWorkflowService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;
    private final PlatformIdentifierGenerator identifierGenerator;

    @Override
    @Transactional
    public ScheduledInspection schedule(UUID submissionId, UUID listenerId) {
        UUID workId = identifierGenerator.generate();
        UUID messageId = identifierGenerator.generate();
        Timestamp now = Timestamp.from(identityClock.instant());
        jdbcTemplate.update(
                """
                INSERT INTO inspection_work (work_id, listener_id, submission_id, operation_key, state, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                """,
                workId,
                listenerId,
                submissionId,
                "inspect-" + workId,
                now);
        jdbcTemplate.update(
                """
                INSERT INTO admission_outbox (
                    message_id, message_type, schema_version, aggregate_id, work_id, created_at
                ) VALUES (?, 'INSPECT_SUBMISSION', 1, ?, ?, ?)
                """,
                messageId,
                submissionId,
                workId,
                now);
        return new ScheduledInspection(workId, messageId);
    }

    @Override
    @Transactional
    public Delivery acceptDelivery(UUID messageId, UUID workId) {
        Integer valid = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admission_outbox WHERE message_id = ? AND work_id = ?",
                Integer.class,
                messageId,
                workId);
        if (valid == null || valid != 1) {
            throw new IllegalArgumentException("Unknown inspection delivery");
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO inspection_inbox (message_id, work_id, accepted_at)
                VALUES (?, ?, ?) ON CONFLICT (message_id) DO NOTHING
                """,
                messageId,
                workId,
                Timestamp.from(identityClock.instant()));
        return new Delivery(workId, inserted == 0);
    }

    @Override
    @Transactional
    public Claim claim(UUID workId, String workerId, Instant leaseUntil, String operationKey) {
        StoredWork work = lock(workId);
        if (!work.operationKey().equals(operationKey)) {
            throw new IllegalArgumentException("Inspection operation does not match its durable work");
        }
        if (work.completed()) {
            return new Claim(work.submissionId(), ClaimStatus.COMPLETED);
        }
        Integer accepted = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inspection_inbox WHERE work_id = ?",
                Integer.class,
                workId);
        if (accepted == null || accepted == 0) {
            throw new IllegalStateException("Inspection delivery has not been accepted");
        }
        Instant now = identityClock.instant();
        if (work.leaseExpiresAt() != null
                && work.leaseExpiresAt().isAfter(now)
                && !workerId.equals(work.leaseOwner())) {
            return new Claim(work.submissionId(), ClaimStatus.LEASED_BY_ANOTHER_WORKER);
        }
        jdbcTemplate.update(
                """
                UPDATE inspection_work SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?
                WHERE work_id = ?
                """,
                workerId,
                Timestamp.from(leaseUntil),
                workId);
        return new Claim(work.submissionId(), ClaimStatus.CLAIMED);
    }

    @Override
    @Transactional
    public boolean complete(UUID workId, String workerId) {
        StoredWork work = lock(workId);
        if (work.completed()) {
            return false;
        }
        if (!workerId.equals(work.leaseOwner())) {
            throw new IllegalStateException("Inspection lease was lost");
        }
        jdbcTemplate.update(
                """
                UPDATE inspection_work
                SET state = 'COMPLETED', lease_owner = NULL, lease_expires_at = NULL, completed_at = ?
                WHERE work_id = ?
                """,
                Timestamp.from(identityClock.instant()),
                workId);
        return true;
    }

    private StoredWork lock(UUID workId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT submission_id, operation_key, state, lease_owner, lease_expires_at
                FROM inspection_work WHERE work_id = ? FOR UPDATE
                """,
                (resultSet, row) -> new StoredWork(
                        resultSet.getObject("submission_id", UUID.class),
                        resultSet.getString("operation_key"),
                        "COMPLETED".equals(resultSet.getString("state")),
                        resultSet.getString("lease_owner"),
                        resultSet.getObject("lease_expires_at", OffsetDateTime.class) == null
                                ? null
                                : resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant()),
                workId);
    }

    private record StoredWork(
            UUID submissionId,
            String operationKey,
            boolean completed,
            String leaseOwner,
            Instant leaseExpiresAt) {
    }
}
