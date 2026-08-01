package dev.audiobook.platform.narration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NarrationPlanServiceImpl implements NarrationPlanService {

    private static final String SCHEMA_VERSION = "narration-plan-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final EpubNarrationPlanInterpreter interpreter;
    private final NarrationPlanAssetStore assetStore;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final AudiobookConversionService conversionService;
    private final Clock identityClock;

    @Override
    public void prepare(UUID listenerId, UUID conversionId, InputStream admittedEpub) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(admittedEpub, "admittedEpub");
        AudiobookConversionService.AudiobookConversion conversion =
                conversionService.conversion(listenerId, conversionId);
        if (conversion.state() == AudiobookConversionService.ConversionState.AWAITING_REVIEW) {
            return;
        }
        if (hasPlan(listenerId, conversionId)) {
            return;
        }

        EpubNarrationPlanInterpreter.NarrationPlan plan = interpreter.interpret(admittedEpub);
        byte[] serialized = serialize(plan);
        NarrationPlanAssetStore.StoredAsset asset;
        try {
            asset = assetStore.write(conversionId, serialized);
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Plan Working Asset storage is unavailable", exception);
        }
        jdbcTemplate.update(
                """
                INSERT INTO narration.narration_plan (
                    narration_plan_id, listener_id, conversion_id, schema_version,
                    working_asset_ref, asset_sha256, chapter_count, review_item_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversion_id) DO NOTHING
                """,
                identifierGenerator.generate(),
                listenerId,
                conversionId,
                SCHEMA_VERSION,
                asset.reference(),
                asset.sha256(),
                plan.chapters().size(),
                plan.reviewItems().size(),
                Timestamp.from(identityClock.instant()));
    }

    @Override
    public PlanView plan(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        conversionService.conversion(listenerId, conversionId);
        List<StoredPlan> stored = jdbcTemplate.query(
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
        if (stored.isEmpty() || !SCHEMA_VERSION.equals(stored.getFirst().schemaVersion())) {
            throw new IllegalStateException("Narration Plan is not ready");
        }
        byte[] serialized;
        try {
            serialized = assetStore.read(conversionId, stored.getFirst().reference());
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Plan Working Asset storage is unavailable", exception);
        }
        if (!MessageDigest.isEqual(
                NarrationPlanAssetIdentity.sha256(serialized).getBytes(StandardCharsets.US_ASCII),
                stored.getFirst().sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Narration Plan Working Asset integrity check failed");
        }
        EpubNarrationPlanInterpreter.NarrationPlan asset = deserialize(serialized);
        Map<Integer, List<EpubNarrationPlanInterpreter.ReviewItem>> itemsByChapter = asset.reviewItems().stream()
                .collect(Collectors.groupingBy(EpubNarrationPlanInterpreter.ReviewItem::chapterOrdinal));
        List<ChapterView> chapters = new ArrayList<>(asset.chapters().size());
        for (EpubNarrationPlanInterpreter.Chapter chapter : asset.chapters()) {
            chapters.add(new ChapterView(
                    chapter.ordinal(),
                    chapter.title(),
                    provenance(chapter.provenance()),
                    chapter.gaps().stream().map(gap -> new GapView(gap.sourceUnit(), gap.reasonCode())).toList(),
                    itemsByChapter.getOrDefault(chapter.ordinal(), List.of()).stream()
                            .map(NarrationPlanServiceImpl::reviewItem)
                            .toList()));
        }
        return new PlanView(chapters, false);
    }

    private boolean hasPlan(UUID listenerId, UUID conversionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM narration.narration_plan WHERE listener_id = ? AND conversion_id = ?",
                Integer.class,
                listenerId,
                conversionId);
        return count != null && count > 0;
    }

    private byte[] serialize(EpubNarrationPlanInterpreter.NarrationPlan plan) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Narration Plan schema serialization failed", exception);
        }
    }

    private EpubNarrationPlanInterpreter.NarrationPlan deserialize(byte[] plan) {
        try {
            return OBJECT_MAPPER.readValue(plan, EpubNarrationPlanInterpreter.NarrationPlan.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Narration Plan schema validation failed", exception);
        }
    }

    private static ProvenanceView provenance(EpubNarrationPlanInterpreter.StructuralProvenance provenance) {
        return new ProvenanceView(
                provenance.source().name(),
                provenance.spineIndex(),
                provenance.spineItem(),
                provenance.anchor(),
                provenance.sourceDeclared(),
                provenance.confidence().value());
    }

    private static ReviewItemView reviewItem(EpubNarrationPlanInterpreter.ReviewItem item) {
        return new ReviewItemView(
                item.ordinal(),
                item.sourceOrdinal(),
                item.type().name(),
                provenance(item.provenance()),
                item.extractionConfidence().value(),
                item.classificationConfidence().value(),
                item.treatmentConfidence().value(),
                item.recommendedTreatment().name(),
                item.narrationSnippet(),
                item.reasonCode());
    }

    private record StoredPlan(String reference, String sha256, String schemaVersion) {
    }
}
