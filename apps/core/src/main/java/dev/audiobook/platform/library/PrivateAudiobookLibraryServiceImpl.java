package dev.audiobook.platform.library;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PrivateAudiobookLibraryServiceImpl implements PrivateAudiobookLibraryService {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public PrivateAudiobook find(UUID listenerId, UUID conversionId) {
        List<PrivateAudiobook> matches = jdbcTemplate.query(
                """
                SELECT pa.audiobook_id, pa.current_asset_version_id, pa.availability,
                       av.manifest_digest, av.total_duration_ms
                FROM library.private_audiobook pa
                JOIN library.audiobook_asset_version av
                  ON av.asset_version_id = pa.current_asset_version_id
                WHERE pa.listener_id = ? AND pa.conversion_id = ?
                """,
                (resultSet, row) -> new PrivateAudiobook(
                        resultSet.getObject("audiobook_id", UUID.class),
                        resultSet.getObject("current_asset_version_id", UUID.class),
                        resultSet.getString("availability"),
                        resultSet.getString("manifest_digest"),
                        resultSet.getLong("total_duration_ms")),
                listenerId,
                conversionId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    @Override
    public void publish(Publication publication) {
        Timestamp now = Timestamp.from(publication.createdAt());
        jdbcTemplate.update(
                """
                INSERT INTO library.private_audiobook (
                    audiobook_id, listener_id, conversion_id, availability, created_at
                ) VALUES (?, ?, ?, 'AVAILABLE', ?)
                """,
                publication.audiobookId(), publication.listenerId(), publication.conversionId(), now);
        jdbcTemplate.update(
                """
                INSERT INTO library.audiobook_asset_version (
                    asset_version_id, audiobook_id, listener_id, generation_recipe_id,
                    recipe_digest, manifest_object_key, manifest_digest,
                    packaging_profile_version, total_duration_ms, total_bytes,
                    integrated_loudness_lufs, true_peak_dbtp, applied_gain_db, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publication.assetVersionId(),
                publication.audiobookId(),
                publication.listenerId(),
                publication.recipeId(),
                publication.recipeDigest(),
                publication.manifestObjectKey(),
                publication.manifestDigest(),
                publication.packagingProfileVersion(),
                publication.totalDurationMs(),
                publication.totalBytes(),
                publication.integratedLoudnessLufs(),
                publication.truePeakDbtp(),
                publication.appliedGainDb(),
                now);
        for (Chapter chapter : publication.chapters()) {
            jdbcTemplate.update(
                    """
                    INSERT INTO library.audiobook_chapter (
                        chapter_id, asset_version_id, listener_id, chapter_ordinal,
                        display_title, start_ms, duration_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    chapter.chapterId(),
                    publication.assetVersionId(),
                    publication.listenerId(),
                    chapter.ordinal(),
                    chapter.displayTitle(),
                    chapter.startMs(),
                    chapter.durationMs());
            for (Part part : chapter.parts()) {
                jdbcTemplate.update(
                        """
                        INSERT INTO library.final_asset_part (
                            part_id, chapter_id, asset_version_id, listener_id,
                            chapter_ordinal, part_ordinal, object_key, mime_type,
                            byte_length, duration_ms, sha256
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        part.partId(),
                        chapter.chapterId(),
                        publication.assetVersionId(),
                        publication.listenerId(),
                        chapter.ordinal(),
                        part.ordinal(),
                        part.objectKey(),
                        part.mimeType(),
                        part.byteLength(),
                        part.durationMs(),
                        part.sha256());
            }
        }
        jdbcTemplate.update(
                """
                UPDATE library.private_audiobook
                SET current_asset_version_id = ?
                WHERE audiobook_id = ? AND listener_id = ?
                """,
                publication.assetVersionId(), publication.audiobookId(), publication.listenerId());
    }
}
