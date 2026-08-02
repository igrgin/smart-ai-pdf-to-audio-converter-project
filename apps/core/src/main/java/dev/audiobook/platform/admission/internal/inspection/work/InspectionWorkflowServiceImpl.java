package dev.audiobook.platform.admission.internal.inspection.work;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final InspectionProperties inspectionProperties;

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
        return jdbcTemplate.queryForObject(
                "SELECT submission_id, claim_status FROM workflow.claim_inspection(?, ?, ?, ?, ?)",
                (resultSet, row) -> new Claim(
                        resultSet.getObject("submission_id", UUID.class),
                        ClaimStatus.valueOf(resultSet.getString("claim_status"))),
                workId,
                workerId,
                Timestamp.from(leaseUntil),
                operationKey,
                inspectionProperties.maximumAttempts());
    }

    @Override
    public List<PendingInspection> pending(Instant availableAt, int limit) {
        if (availableAt == null || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("Pending inspection query is outside the allowed range");
        }
        return jdbcTemplate.query(
                "SELECT work_id, operation_key FROM workflow.pending_inspections(?, ?)",
                (resultSet, row) -> new PendingInspection(
                        resultSet.getObject("work_id", UUID.class),
                        resultSet.getString("operation_key")),
                Timestamp.from(availableAt),
                limit);
    }

}
