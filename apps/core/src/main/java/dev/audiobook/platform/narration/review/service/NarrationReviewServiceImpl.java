package dev.audiobook.platform.narration.review.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationReviewAssetStore;
import dev.audiobook.platform.narration.planning.assets.NarrationPlanAssetIdentity;
import dev.audiobook.platform.narration.planning.service.NarrationPlanService;
import dev.audiobook.platform.narration.review.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NarrationReviewServiceImpl implements NarrationReviewService {

    private static final String SCHEMA_VERSION = "narration-review-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final int MAX_SECTIONS = 400;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_SNIPPET_LENGTH = 4_000;

    private final JdbcTemplate jdbcTemplate;
    private final NarrationPlanService narrationPlanService;
    private final NarrationReviewAssetStore assetStore;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final Clock identityClock;

    @Override
    @Transactional
    public ReviewResult submit(ReviewCommand command) {
        validateCommand(command);
        String requestFingerprint = fingerprint(command);
        StoredOperation replay = findOperation(command.listenerId(), command.operationKey());
        if (replay != null) {
            return replay(command, requestFingerprint, replay);
        }

        StoredConversion conversion = lockConversion(command.listenerId(), command.conversionId());
        replay = findOperation(command.listenerId(), command.operationKey());
        if (replay != null) {
            return replay(command, requestFingerprint, replay);
        }
        if (conversion.version() != command.expectedConversionVersion()) {
            throw rejected(
                    NarrationReviewRejectionReason.CONVERSION_VERSION_MISMATCH,
                    conversion.version());
        }
        if (!"AWAITING_REVIEW".equals(conversion.state())
                || !"NARRATION_REVIEW_AVAILABLE".equals(conversion.reasonCode())) {
            throw rejected(NarrationReviewRejectionReason.REVIEW_NOT_AVAILABLE);
        }

        NarrationPlanService.PlanView plan =
                narrationPlanService.plan(command.listenerId(), command.conversionId());
        List<SectionDecision> frozenSections =
                command.action() == ReviewAction.SKIP_OPTIONAL
                        ? recommendedSections(plan)
                        : validatedSubmittedSections(plan, command.sections());
        byte[] frozenAsset =
                serialize(new FrozenReview(SCHEMA_VERSION, command.action(), frozenSections));
        UUID decisionId = identifierGenerator.generate();
        NarrationReviewAssetStore.StoredAsset storedAsset;
        try {
            storedAsset = assetStore.write(command.conversionId(), decisionId, frozenAsset);
        } catch (IOException exception) {
            throw rejected(NarrationReviewRejectionReason.WORKING_ASSET_UNAVAILABLE);
        }

        long resultVersion = conversion.version() + 1;
        Timestamp now = Timestamp.from(identityClock.instant());
        int reviewItemCount =
                frozenSections.stream().mapToInt(section -> section.reviewItems().size()).sum();
        jdbcTemplate.update(
                """
                INSERT INTO narration.narration_review_decision (
                    decision_id, listener_id, conversion_id, action, schema_version,
                    working_asset_ref, asset_sha256, section_count, review_item_count,
                    source_version, result_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decisionId,
                command.listenerId(),
                command.conversionId(),
                command.action().name(),
                SCHEMA_VERSION,
                storedAsset.reference(),
                storedAsset.sha256(),
                frozenSections.size(),
                reviewItemCount,
                conversion.version(),
                resultVersion,
                now);
        String reasonCode =
                command.action() == ReviewAction.SKIP_OPTIONAL
                        ? "NARRATION_RECOMMENDATIONS_ACCEPTED"
                        : "NARRATION_REVIEW_APPROVED";
        int updated =
                jdbcTemplate.update(
                        """
                        UPDATE workflow.audiobook_conversion
                        SET reason_code = ?, version = ?
                        WHERE conversion_id = ? AND listener_id = ?
                          AND state = 'AWAITING_REVIEW'
                          AND reason_code = 'NARRATION_REVIEW_AVAILABLE'
                          AND version = ?
                        """,
                        reasonCode,
                        resultVersion,
                        command.conversionId(),
                        command.listenerId(),
                        conversion.version());
        if (updated != 1) {
            throw rejected(
                    NarrationReviewRejectionReason.CONVERSION_VERSION_MISMATCH,
                    currentVersion(command));
        }
        jdbcTemplate.update(
                """
                INSERT INTO narration.narration_review_operation (
                    listener_id, operation_key, conversion_id, request_fingerprint, decision_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                command.listenerId(),
                command.operationKey(),
                command.conversionId(),
                requestFingerprint,
                decisionId,
                now);
        return new ReviewResult(decisionId, command.action(), resultVersion, false);
    }

    private static void validateCommand(ReviewCommand command) {
        if (command == null
                || command.listenerId() == null
                || command.conversionId() == null
                || command.action() == null
                || command.operationKey() == null
                || command.operationKey().isBlank()
                || command.operationKey().length() > 200
                || command.expectedConversionVersion() < 0) {
            throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
        }
        if (command.action() == ReviewAction.SKIP_OPTIONAL && !command.sections().isEmpty()) {
            throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
        }
    }

    private List<SectionDecision> recommendedSections(NarrationPlanService.PlanView plan) {
        return plan.chapters().stream()
                .map(
                        chapter ->
                                new SectionDecision(
                                        "section-" + chapter.ordinal(),
                                        sectionTitle(chapter),
                                        false,
                                        List.of(chapter.ordinal()),
                                        chapter.reviewItems().stream()
                                                .map(
                                                        item ->
                                                                new ReviewItemDecision(
                                                                        chapter.ordinal(),
                                                                        item.ordinal(),
                                                                        Treatment.valueOf(
                                                                                item
                                                                                        .recommendedTreatment()),
                                                                        item.narrationSnippet()))
                                                .toList()))
                .toList();
    }

    static List<SectionDecision> validatedSubmittedSections(
            NarrationPlanService.PlanView plan, List<SectionDecision> sections) {
        if (sections == null || sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
        }
        Set<Integer> sourceChapters =
                plan.chapters().stream()
                        .map(NarrationPlanService.ChapterView::ordinal)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<ItemKey, NarrationPlanService.ReviewItemView> expectedItems = new HashMap<>();
        for (NarrationPlanService.ChapterView chapter : plan.chapters()) {
            for (NarrationPlanService.ReviewItemView item : chapter.reviewItems()) {
                expectedItems.put(new ItemKey(chapter.ordinal(), item.ordinal()), item);
            }
        }

        Set<String> clientIds = new HashSet<>();
        Set<Integer> representedChapters = new HashSet<>();
        Set<ItemKey> representedItems = new HashSet<>();
        List<SectionDecision> copy = new ArrayList<>(sections.size());
        for (SectionDecision section : sections) {
            if (section == null
                    || section.clientId() == null
                    || section.clientId().isBlank()
                    || section.clientId().length() > 120
                    || !clientIds.add(section.clientId())
                    || section.title() == null
                    || section.title().isBlank()
                    || section.title().length() > MAX_TITLE_LENGTH
                    || section.sourceChapterOrdinals().isEmpty()
                    || section.sourceChapterOrdinals().stream().anyMatch(Objects::isNull)
                    || !sourceChapters.containsAll(section.sourceChapterOrdinals())) {
                throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
            }
            representedChapters.addAll(section.sourceChapterOrdinals());
            for (ReviewItemDecision item : section.reviewItems()) {
                if (item == null
                        || item.treatment() == null
                        || item.narrationSnippet() != null
                                && item.narrationSnippet().length() > MAX_SNIPPET_LENGTH) {
                    throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
                }
                ItemKey key = new ItemKey(item.sourceChapterOrdinal(), item.ordinal());
                if (!section.sourceChapterOrdinals().contains(item.sourceChapterOrdinal())
                        || !expectedItems.containsKey(key)
                        || !representedItems.add(key)) {
                    throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
                }
            }
            copy.add(section);
        }
        if (!representedChapters.containsAll(sourceChapters)
                || !representedItems.equals(expectedItems.keySet())) {
            throw rejected(NarrationReviewRejectionReason.INVALID_REVIEW);
        }
        return List.copyOf(copy);
    }

    private StoredConversion lockConversion(UUID listenerId, UUID conversionId) {
        List<StoredConversion> conversions =
                jdbcTemplate.query(
                        """
                        SELECT state, reason_code, version
                        FROM workflow.audiobook_conversion
                        WHERE listener_id = ? AND conversion_id = ?
                        FOR UPDATE
                        """,
                        (resultSet, row) ->
                                new StoredConversion(
                                        resultSet.getString("state"),
                                        resultSet.getString("reason_code"),
                                        resultSet.getLong("version")),
                        listenerId,
                        conversionId);
        if (conversions.isEmpty()) {
            throw rejected(NarrationReviewRejectionReason.CONVERSION_UNAVAILABLE);
        }
        return conversions.getFirst();
    }

    private StoredOperation findOperation(UUID listenerId, String operationKey) {
        List<StoredOperation> operations =
                jdbcTemplate.query(
                        """
                        SELECT operation.conversion_id, operation.request_fingerprint,
                               decision.decision_id, decision.action, decision.result_version
                        FROM narration.narration_review_operation operation
                        JOIN narration.narration_review_decision decision USING (decision_id)
                        WHERE operation.listener_id = ? AND operation.operation_key = ?
                        """,
                        (resultSet, row) ->
                                new StoredOperation(
                                        resultSet.getObject("conversion_id", UUID.class),
                                        resultSet.getString("request_fingerprint"),
                                        resultSet.getObject("decision_id", UUID.class),
                                        ReviewAction.valueOf(resultSet.getString("action")),
                                        resultSet.getLong("result_version")),
                        listenerId,
                        operationKey);
        return operations.isEmpty() ? null : operations.getFirst();
    }

    private static ReviewResult replay(
            ReviewCommand command, String requestFingerprint, StoredOperation operation) {
        if (!operation.conversionId().equals(command.conversionId())
                || !operation.requestFingerprint().equals(requestFingerprint)) {
            throw rejected(NarrationReviewRejectionReason.IDEMPOTENCY_KEY_REUSED);
        }
        return new ReviewResult(
                operation.decisionId(), operation.action(), operation.resultVersion(), true);
    }

    private long currentVersion(ReviewCommand command) {
        Long version =
                jdbcTemplate.queryForObject(
                        """
                        SELECT version FROM workflow.audiobook_conversion
                        WHERE listener_id = ? AND conversion_id = ?
                        """,
                        Long.class,
                        command.listenerId(),
                        command.conversionId());
        return version == null ? command.expectedConversionVersion() : version;
    }

    private String fingerprint(ReviewCommand command) {
        return NarrationPlanAssetIdentity.sha256(serialize(command));
    }

    private byte[] serialize(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Narration Review schema serialization failed", exception);
        }
    }

    private static String sectionTitle(NarrationPlanService.ChapterView chapter) {
        return chapter.title() == null || chapter.title().isBlank()
                ? "Source section " + (chapter.ordinal() + 1)
                : chapter.title();
    }

    private static NarrationReviewRejectedException rejected(
            NarrationReviewRejectionReason reason) {
        return new NarrationReviewRejectedException(reason);
    }

    private static NarrationReviewRejectedException rejected(
            NarrationReviewRejectionReason reason, long currentVersion) {
        return new NarrationReviewRejectedException(reason, currentVersion);
    }

    private record FrozenReview(
            String schemaVersion, ReviewAction action, List<SectionDecision> sections) {}

    private record ItemKey(int sourceChapterOrdinal, int ordinal) {}

    private record StoredConversion(String state, String reasonCode, long version) {}

    private record StoredOperation(
            UUID conversionId,
            String requestFingerprint,
            UUID decisionId,
            ReviewAction action,
            long resultVersion) {}
}
