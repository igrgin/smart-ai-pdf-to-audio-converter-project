package dev.audiobook.platform.narration.planning.persistence;

import dev.audiobook.platform.narration.planning.service.NarrationPlanService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JdbcNarrationPlanRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(
            UUID narrationPlanId,
            UUID listenerId,
            UUID conversionId,
            String schemaVersion,
            String reference,
            String digest,
            int chapterCount,
            int reviewItemCount,
            Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO narration.narration_plan (
                    narration_plan_id, listener_id, conversion_id, schema_version,
                    working_asset_ref, asset_sha256, chapter_count, review_item_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversion_id) DO NOTHING
                """,
                narrationPlanId,
                listenerId,
                conversionId,
                schemaVersion,
                reference,
                digest,
                chapterCount,
                reviewItemCount,
                Timestamp.from(createdAt));
    }

    public List<UUID> existingConversionIds(List<UUID> conversionIds) {
        String placeholders = String.join(", ", Collections.nCopies(conversionIds.size(), "?"));
        return jdbcTemplate.query(
                "SELECT conversion_id FROM narration.narration_plan WHERE conversion_id IN ("
                        + placeholders
                        + ")",
                (resultSet, row) -> resultSet.getObject("conversion_id", UUID.class),
                conversionIds.toArray());
    }

    public List<StoredPlan> plans(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.query(
                """
                SELECT working_asset_ref, asset_sha256, schema_version
                FROM narration.narration_plan WHERE listener_id = ? AND conversion_id = ?
                """,
                (resultSet, row) -> new StoredPlan(
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256"),
                        resultSet.getString("schema_version")),
                listenerId,
                conversionId);
    }

    public boolean exists(UUID listenerId, UUID conversionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM narration.narration_plan WHERE listener_id = ? AND conversion_id = ?",
                Integer.class,
                listenerId,
                conversionId);
        return count != null && count > 0;
    }

    public NarrationPlanService.PreparedPlan preparedPlan(UUID listenerId, UUID conversionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT working_asset_ref, asset_sha256
                FROM narration.narration_plan
                WHERE listener_id = ? AND conversion_id = ?
                """,
                (resultSet, row) -> new NarrationPlanService.PreparedPlan(
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256")),
                listenerId,
                conversionId);
    }

    public record StoredPlan(String reference, String sha256, String schemaVersion) {
    }
}
