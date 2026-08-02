package dev.audiobook.platform.retention.restore.persistence;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

@Component
@RequiredArgsConstructor
public class JdbcRestoreReplayIncidentPersistence implements RestoreReplayIncidentPersistence {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final Clock identityClock;

    @Override
    public void resolveFailure() {
        jdbcTemplate.update(
                "UPDATE retention.compliance_incident SET resolved_at = ?"
                        + " WHERE request_id IS NULL"
                        + " AND incident_code = 'RESTORE_TOMBSTONE_REPLAY_FAILED'"
                        + " AND resolved_at IS NULL",
                Timestamp.from(identityClock.instant()));
    }

    @Override
    public void recordFailure() {
        var now = Timestamp.from(identityClock.instant());
        jdbcTemplate.update(
                """
                INSERT INTO retention.compliance_incident (
                    incident_id, request_id, incident_code, detected_at, deadline
                ) VALUES (?, NULL, 'RESTORE_TOMBSTONE_REPLAY_FAILED', ?, ?)
                ON CONFLICT (request_id, incident_code) DO UPDATE SET resolved_at = NULL
                """,
                identifierGenerator.generate(),
                now,
                now);
    }
}
