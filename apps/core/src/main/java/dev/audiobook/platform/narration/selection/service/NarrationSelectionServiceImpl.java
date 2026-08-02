package dev.audiobook.platform.narration.selection.service;

import static dev.audiobook.platform.narration.NarrationRejectionReason.AUDIOBOOK_CONVERSION_NOT_FOUND;
import static dev.audiobook.platform.narration.NarrationRejectionReason.CAPABILITY_PROFILE_STALE;
import static dev.audiobook.platform.narration.NarrationRejectionReason.CONVERSION_VERSION_MISMATCH;
import static dev.audiobook.platform.narration.NarrationRejectionReason.EXPLICIT_NEW_CHOICE_REQUIRED;
import static dev.audiobook.platform.narration.NarrationRejectionReason.GENERATION_RECIPE_ALREADY_CONFIRMED;
import static dev.audiobook.platform.narration.NarrationRejectionReason.GENERATION_RECIPE_REQUIRED;
import static dev.audiobook.platform.narration.NarrationRejectionReason.IDEMPOTENCY_KEY_REUSED;
import static dev.audiobook.platform.narration.NarrationRejectionReason.QUALIFIED_FAILOVER_UNAVAILABLE;
import static dev.audiobook.platform.narration.NarrationRejectionReason.UNISSUED_VOICE_IDENTIFIER;
import static dev.audiobook.platform.narration.NarrationRejectionReason.UNSUPPORTED_NARRATION_PACE;
import static dev.audiobook.platform.narration.NarrationRejectionReason.UNSUPPORTED_REGION_OR_DATA_POLICY;
import static dev.audiobook.platform.narration.NarrationRejectionReason.VOICE_MAPPING_STALE;
import static dev.audiobook.platform.narration.NarrationRejectionReason.VOICE_MAPPING_UNAVAILABLE;
import static dev.audiobook.platform.narration.NarrationRejectionReason.VOICE_RETIRED;
import static dev.audiobook.platform.narration.NarrationRejectionReason.VOICE_TEMPORARILY_UNAVAILABLE;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationRejectionReason;
import dev.audiobook.platform.narration.selection.*;
import dev.audiobook.platform.narration.selection.error.exception.NarrationSelectionRejectedException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NarrationSelectionServiceImpl implements NarrationSelectionService {

    private static final String SEGMENTATION_POLICY_VERSION = "semantic-segments-v1";
    private static final String AUDIO_POLICY_VERSION = "mono-24k-mp3-v1";
    private static final String TOOLCHAIN_VERSION = "speech-worker-ffmpeg-v1";
    private static final String FROZEN_RECIPE_ELIGIBILITY_SQL =
            """
            vm.mapping_state = 'CURRENT'
              AND p.profile_state = 'CURRENT'
              AND p.privacy_state = 'QUALIFIED'
              AND p.region_state = 'QUALIFIED'
              AND p.access_state = 'QUALIFIED'
              AND p.quota_state = 'QUALIFIED'
              AND p.evaluation_state = 'QUALIFIED'
              AND p.expires_at > ?
              AND vm.narrator_voice_id = gr.narrator_voice_id
              AND vm.mapping_version = gr.mapping_version
              AND vm.provider_voice = gr.provider_voice
              AND vm.preview_version = gr.preview_version
              AND vm.evaluation_version = gr.evaluation_version
              AND vm.required_region = gr.region
              AND vm.required_data_policy_version = gr.data_policy_version
              AND p.profile_version = gr.capability_profile_version
              AND p.provider = gr.provider
              AND p.service = gr.service
              AND p.endpoint = gr.endpoint
              AND p.model_snapshot = gr.model_snapshot
              AND p.region = gr.region
              AND p.data_policy_version = gr.data_policy_version
              AND gr.pace = ANY(p.supported_paces)
              AND vm.native_controls -> gr.pace = gr.native_controls
            """;
    private static final RowMapper<NarratorVoice> NARRATOR_VOICE_ROW_MAPPER =
            (resultSet, row) ->
                    new NarratorVoice(
                            resultSet.getObject("voice_id", UUID.class),
                            resultSet.getString("display_name"),
                            resultSet.getString("english_variety"),
                            List.of(
                                    resultSet.getString("descriptor_primary"),
                                    resultSet.getString("descriptor_secondary")),
                            resultSet.getString("descriptor_review_version"),
                            VoiceAvailability.valueOf(resultSet.getString("availability")),
                            new VoicePreview(
                                    resultSet.getString("preview_uri"),
                                    resultSet.getString("preview_passage_version"),
                                    resultSet.getInt("preview_duration_seconds"),
                                    resultSet.getBoolean("preview_ai_generated")));

    private final JdbcTemplate jdbcTemplate;
    private final Clock identityClock;
    private final PlatformIdentifierGenerator identifierGenerator;

    @Override
    public VoiceCatalog catalog() {
        List<NarratorVoice> voices =
                jdbcTemplate.query(
                        """
                        SELECT voice_id, display_name, english_variety, descriptor_primary,
                               descriptor_secondary, descriptor_review_version, availability,
                               preview_uri, preview_passage_version, preview_duration_seconds,
                               preview_ai_generated
                        FROM narration.narrator_voice ORDER BY catalog_ordinal
                        """,
                        NARRATOR_VOICE_ROW_MAPPER);
        return new VoiceCatalog(voices, List.of(NarrationPace.values()), NarrationPace.NATURAL);
    }

    @Override
    @Transactional
    public ConfirmedRecipe confirm(ConfirmCommand command) {
        validate(command);
        String requestFingerprint = fingerprint(command);
        StoredOperation replay = findOperation(command.operationKey());
        if (replay != null) {
            if (!replay.requestFingerprint().equals(requestFingerprint)) {
                throw rejected(IDEMPOTENCY_KEY_REUSED);
            }
            return confirmedRecipe(replay.recipeId());
        }

        StoredConversion conversion = lockConversion(command.listenerId(), command.conversionId());
        replay = findOperation(command.operationKey());
        if (replay != null) {
            if (!replay.requestFingerprint().equals(requestFingerprint)) {
                throw rejected(IDEMPOTENCY_KEY_REUSED);
            }
            return confirmedRecipe(replay.recipeId());
        }
        if (conversion.version() != command.expectedConversionVersion()) {
            throw rejected(CONVERSION_VERSION_MISMATCH);
        }
        NarratorVoice voice = narratorVoice(command.voiceId());
        if (voice.availability() != VoiceAvailability.AVAILABLE) {
            throw rejected(
                    switch (voice.availability()) {
                        case TEMPORARILY_UNAVAILABLE -> VOICE_TEMPORARILY_UNAVAILABLE;
                        case RETIRED -> VOICE_RETIRED;
                        case AVAILABLE ->
                                throw new IllegalStateException(
                                        "Available voice cannot be rejected for availability");
                    });
        }
        if (conversion.currentRecipeId() != null
                && eligibleForGeneration(conversion.currentRecipeId())) {
            throw rejected(GENERATION_RECIPE_ALREADY_CONFIRMED);
        }

        EligibleMapping mapping =
                eligibleMapping(
                        command.voiceId(), command.pace(), voice.preview().passageVersion());
        UUID recipeId = identifierGenerator.generate();
        Instant createdAt = identityClock.instant();
        String recipeDigest = recipeDigest(recipeId, command, mapping, createdAt);
        jdbcTemplate.update(
                """
                INSERT INTO narration.generation_recipe (
                    recipe_id, conversion_id, listener_id, supersedes_recipe_id,
                    narrator_voice_id, voice_display_name, pace,
                    capability_profile_id, capability_profile_version, provider, service, endpoint,
                    model_snapshot, region, data_policy_version,
                    voice_mapping_id, mapping_version, provider_voice, native_controls,
                    preview_version, evaluation_version, segmentation_policy_version,
                    audio_policy_version, toolchain_version, recipe_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                          ?, ?, ?, ?, ?, ?, ?)
                """,
                recipeId,
                command.conversionId(),
                command.listenerId(),
                conversion.currentRecipeId(),
                command.voiceId(),
                voice.displayName(),
                command.pace().name(),
                mapping.profileId(),
                mapping.profileVersion(),
                mapping.provider(),
                mapping.service(),
                mapping.endpoint(),
                mapping.modelSnapshot(),
                mapping.region(),
                mapping.dataPolicyVersion(),
                mapping.mappingId(),
                mapping.mappingVersion(),
                mapping.providerVoice(),
                mapping.nativeControls(),
                mapping.previewVersion(),
                mapping.evaluationVersion(),
                SEGMENTATION_POLICY_VERSION,
                AUDIO_POLICY_VERSION,
                TOOLCHAIN_VERSION,
                recipeDigest,
                Timestamp.from(createdAt));
        long nextVersion = conversion.version() + 1;
        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET current_generation_recipe_id = ?, version = ?
                WHERE conversion_id = ? AND listener_id = ?
                """,
                recipeId,
                nextVersion,
                command.conversionId(),
                command.listenerId());
        jdbcTemplate.update(
                """
                INSERT INTO narration.recipe_confirmation_operation (
                    operation_key, listener_id, conversion_id, request_fingerprint, recipe_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                command.operationKey(),
                command.listenerId(),
                command.conversionId(),
                requestFingerprint,
                recipeId,
                Timestamp.from(createdAt));
        return new ConfirmedRecipe(
                recipeId,
                command.conversionId(),
                command.voiceId(),
                voice.displayName(),
                command.pace(),
                recipeDigest,
                nextVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public NarrationChoiceStatus narrationChoice(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        List<StoredNarrationChoice> choices =
                jdbcTemplate.query(
                        """
                        SELECT ac.version, ac.current_generation_recipe_id, gr.narrator_voice_id,
                               gr.voice_display_name, gr.pace
                        FROM workflow.audiobook_conversion ac
                        LEFT JOIN narration.generation_recipe gr
                          ON gr.recipe_id = ac.current_generation_recipe_id
                        WHERE ac.conversion_id = ? AND ac.listener_id = ?
                        """,
                        (resultSet, row) ->
                                new StoredNarrationChoice(
                                        resultSet.getLong("version"),
                                        resultSet.getObject(
                                                "current_generation_recipe_id", UUID.class),
                                        resultSet.getObject("narrator_voice_id", UUID.class),
                                        resultSet.getString("voice_display_name"),
                                        resultSet.getString("pace") == null
                                                ? null
                                                : NarrationPace.valueOf(
                                                        resultSet.getString("pace"))),
                        conversionId,
                        listenerId);
        if (choices.isEmpty()) {
            throw rejected(AUDIOBOOK_CONVERSION_NOT_FOUND);
        }
        StoredNarrationChoice choice = choices.getFirst();
        boolean explicitChoiceRequired =
                choice.recipeId() == null || !eligibleForGeneration(choice.recipeId());
        return new NarrationChoiceStatus(
                choice.version(),
                choice.recipeId(),
                choice.voiceId(),
                choice.voiceDisplayName(),
                choice.pace(),
                explicitChoiceRequired);
    }

    @Override
    @Transactional(readOnly = true)
    public GenerationAuthorization authorizeGeneration(UUID listenerId, UUID conversionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        List<ActiveRecipe> recipes =
                jdbcTemplate.query(
                        """
                        SELECT gr.recipe_id, gr.recipe_digest,
                        """
                                + FROZEN_RECIPE_ELIGIBILITY_SQL
                                + """
                                       AS eligible
                                FROM workflow.audiobook_conversion ac
                                JOIN narration.generation_recipe gr ON gr.recipe_id = ac.current_generation_recipe_id
                                JOIN narration.voice_mapping vm ON vm.mapping_id = gr.voice_mapping_id
                                JOIN narration.provider_capability_profile p ON p.profile_id = gr.capability_profile_id
                                WHERE ac.conversion_id = ? AND ac.listener_id = ?
                                """,
                        (resultSet, row) ->
                                new ActiveRecipe(
                                        resultSet.getObject("recipe_id", UUID.class),
                                        resultSet.getString("recipe_digest"),
                                        resultSet.getBoolean("eligible")),
                        Timestamp.from(identityClock.instant()),
                        conversionId,
                        listenerId);
        if (recipes.isEmpty()) {
            throw rejected(GENERATION_RECIPE_REQUIRED);
        }
        ActiveRecipe recipe = recipes.getFirst();
        if (!recipe.eligible()) {
            throw rejected(EXPLICIT_NEW_CHOICE_REQUIRED);
        }
        return new GenerationAuthorization(recipe.recipeId(), recipe.recipeDigest());
    }

    @Override
    @Transactional
    public FailoverAuthorization failoverGeneration(
            UUID listenerId, UUID conversionId, UUID failedRecipeId) {
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(conversionId, "conversionId");
        Objects.requireNonNull(failedRecipeId, "failedRecipeId");
        StoredConversion conversion = lockConversion(listenerId, conversionId);
        if (!failedRecipeId.equals(conversion.currentRecipeId())) {
            throw rejected(QUALIFIED_FAILOVER_UNAVAILABLE);
        }
        Instant now = identityClock.instant();
        List<FailoverCandidate> candidates =
                jdbcTemplate.query(
                        """
                        SELECT gr.narrator_voice_id, gr.voice_display_name, gr.pace,
                               fm.mapping_id, fm.mapping_version, fm.provider_voice,
                               (fm.native_controls -> gr.pace)::text AS selected_controls,
                               fm.preview_version, fm.evaluation_version,
                               fp.profile_id, fp.profile_version, fp.provider, fp.service, fp.endpoint,
                               fp.model_snapshot, fp.region, fp.data_policy_version,
                               q.voice_equivalence_version, q.pace_equivalence_version,
                               gr.segmentation_policy_version, gr.audio_policy_version, gr.toolchain_version
                        FROM narration.generation_recipe gr
                        JOIN narration.qualified_voice_equivalence q
                          ON q.primary_mapping_id = gr.voice_mapping_id
                        JOIN narration.voice_mapping fm ON fm.mapping_id = q.failover_mapping_id
                        JOIN narration.provider_capability_profile fp
                          ON fp.profile_id = fm.capability_profile_id
                        WHERE gr.recipe_id = ? AND gr.listener_id = ? AND gr.conversion_id = ?
                          AND q.evaluation_state = 'QUALIFIED' AND q.expires_at > ?
                          AND q.pace = gr.pace
                          AND fm.narrator_voice_id = gr.narrator_voice_id
                          AND fm.mapping_state = 'CURRENT'
                          AND fm.required_region = fp.region
                          AND fm.required_data_policy_version = fp.data_policy_version
                          AND fp.service = 'speech' AND fp.profile_state = 'CURRENT'
                          AND fp.privacy_state = 'QUALIFIED' AND fp.region_state = 'QUALIFIED'
                          AND fp.access_state = 'QUALIFIED' AND fp.quota_state = 'QUALIFIED'
                          AND fp.evaluation_state = 'QUALIFIED' AND fp.expires_at > ?
                          AND gr.pace = ANY(fp.supported_paces)
                          AND fm.native_controls -> gr.pace IS NOT NULL
                        ORDER BY q.checked_at DESC, q.equivalence_id
                        """,
                        (resultSet, row) ->
                                new FailoverCandidate(
                                        resultSet.getObject("narrator_voice_id", UUID.class),
                                        resultSet.getString("voice_display_name"),
                                        resultSet.getString("pace"),
                                        resultSet.getObject("mapping_id", UUID.class),
                                        resultSet.getString("mapping_version"),
                                        resultSet.getString("provider_voice"),
                                        resultSet.getString("selected_controls"),
                                        resultSet.getString("preview_version"),
                                        resultSet.getString("evaluation_version"),
                                        resultSet.getObject("profile_id", UUID.class),
                                        resultSet.getString("profile_version"),
                                        resultSet.getString("provider"),
                                        resultSet.getString("service"),
                                        resultSet.getString("endpoint"),
                                        resultSet.getString("model_snapshot"),
                                        resultSet.getString("region"),
                                        resultSet.getString("data_policy_version"),
                                        resultSet.getString("voice_equivalence_version"),
                                        resultSet.getString("pace_equivalence_version"),
                                        resultSet.getString("segmentation_policy_version"),
                                        resultSet.getString("audio_policy_version"),
                                        resultSet.getString("toolchain_version")),
                        failedRecipeId,
                        listenerId,
                        conversionId,
                        Timestamp.from(now),
                        Timestamp.from(now));
        if (candidates.isEmpty()) {
            throw rejected(QUALIFIED_FAILOVER_UNAVAILABLE);
        }
        FailoverCandidate candidate = candidates.getFirst();
        UUID replacementRecipeId = identifierGenerator.generate();
        Instant createdAt = identityClock.instant();
        String digest =
                sha256(
                        """
                        schemaVersion=failover-1
                        replacementRecipeId=%s
                        failedRecipeId=%s
                        conversionId=%s
                        profileVersion=%s
                        mappingVersion=%s
                        voiceEquivalenceVersion=%s
                        paceEquivalenceVersion=%s
                        createdAt=%s
                        """
                                .formatted(
                                        replacementRecipeId,
                                        failedRecipeId,
                                        conversionId,
                                        candidate.profileVersion(),
                                        candidate.mappingVersion(),
                                        candidate.voiceEquivalenceVersion(),
                                        candidate.paceEquivalenceVersion(),
                                        createdAt));
        jdbcTemplate.update(
                """
                INSERT INTO narration.generation_recipe (
                    recipe_id, conversion_id, listener_id, supersedes_recipe_id,
                    narrator_voice_id, voice_display_name, pace,
                    capability_profile_id, capability_profile_version, provider, service, endpoint,
                    model_snapshot, region, data_policy_version,
                    voice_mapping_id, mapping_version, provider_voice, native_controls,
                    preview_version, evaluation_version, segmentation_policy_version,
                    audio_policy_version, toolchain_version, recipe_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                          ?, ?, ?, ?, ?, ?, ?)
                """,
                replacementRecipeId,
                conversionId,
                listenerId,
                failedRecipeId,
                candidate.voiceId(),
                candidate.voiceDisplayName(),
                candidate.pace(),
                candidate.profileId(),
                candidate.profileVersion(),
                candidate.provider(),
                candidate.service(),
                candidate.endpoint(),
                candidate.model(),
                candidate.region(),
                candidate.dataPolicyVersion(),
                candidate.mappingId(),
                candidate.mappingVersion(),
                candidate.providerVoice(),
                candidate.nativeControls(),
                candidate.previewVersion(),
                candidate.evaluationVersion(),
                candidate.segmentationPolicyVersion(),
                candidate.audioPolicyVersion(),
                candidate.toolchainVersion(),
                digest,
                Timestamp.from(createdAt));
        jdbcTemplate.update(
                """
                UPDATE workflow.audiobook_conversion
                SET current_generation_recipe_id = ?, version = version + 1
                WHERE conversion_id = ? AND listener_id = ?
                """,
                replacementRecipeId,
                conversionId,
                listenerId);
        return new FailoverAuthorization(
                failedRecipeId,
                replacementRecipeId,
                digest,
                candidate.profileVersion(),
                candidate.voiceEquivalenceVersion(),
                candidate.paceEquivalenceVersion());
    }

    private EligibleMapping eligibleMapping(
            UUID voiceId, NarrationPace pace, String currentPreviewVersion) {
        List<MappingCandidate> candidates =
                jdbcTemplate.query(
                        """
                        SELECT vm.mapping_id, vm.mapping_version, vm.provider_voice,
                               (vm.native_controls -> ?)::text AS selected_controls,
                               vm.required_region, vm.required_data_policy_version,
                               vm.preview_version, vm.evaluation_version, vm.mapping_state,
                               p.profile_id, p.profile_version, p.provider, p.service, p.endpoint,
                               p.model_snapshot, p.region, p.data_policy_version, p.profile_state,
                               p.privacy_state, p.region_state, p.access_state, p.quota_state,
                               p.evaluation_state, p.checked_at, p.expires_at,
                               ? = ANY(p.supported_paces) AS supports_pace
                        FROM narration.voice_mapping vm
                        JOIN narration.provider_capability_profile p
                          ON p.profile_id = vm.capability_profile_id
                        WHERE vm.narrator_voice_id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM narration.qualified_voice_equivalence q
                              WHERE q.failover_mapping_id = vm.mapping_id
                          )
                        ORDER BY p.checked_at DESC, vm.mapping_id
                        """,
                        (resultSet, row) ->
                                new MappingCandidate(
                                        resultSet.getObject("mapping_id", UUID.class),
                                        resultSet.getString("mapping_version"),
                                        resultSet.getString("provider_voice"),
                                        resultSet.getString("selected_controls"),
                                        resultSet.getString("required_region"),
                                        resultSet.getString("required_data_policy_version"),
                                        resultSet.getString("preview_version"),
                                        resultSet.getString("evaluation_version"),
                                        resultSet.getString("mapping_state"),
                                        resultSet.getObject("profile_id", UUID.class),
                                        resultSet.getString("profile_version"),
                                        resultSet.getString("provider"),
                                        resultSet.getString("service"),
                                        resultSet.getString("endpoint"),
                                        resultSet.getString("model_snapshot"),
                                        resultSet.getString("region"),
                                        resultSet.getString("data_policy_version"),
                                        resultSet.getString("profile_state"),
                                        resultSet.getString("privacy_state"),
                                        resultSet.getString("region_state"),
                                        resultSet.getString("access_state"),
                                        resultSet.getString("quota_state"),
                                        resultSet.getString("evaluation_state"),
                                        resultSet
                                                .getObject("expires_at", OffsetDateTime.class)
                                                .toInstant(),
                                        resultSet.getBoolean("supports_pace")),
                        pace.name(),
                        pace.name(),
                        voiceId);
        if (candidates.isEmpty()) {
            throw rejected(VOICE_MAPPING_UNAVAILABLE);
        }
        Instant now = identityClock.instant();
        for (MappingCandidate candidate : candidates) {
            if (candidate.eligible(now, currentPreviewVersion)) {
                return candidate.eligibleMapping();
            }
        }
        if (candidates.stream()
                .noneMatch(
                        candidate ->
                                "CURRENT".equals(candidate.mappingState())
                                        && currentPreviewVersion.equals(
                                                candidate.previewVersion()))) {
            throw rejected(VOICE_MAPPING_STALE);
        }
        if (candidates.stream()
                .noneMatch(
                        candidate ->
                                "CURRENT".equals(candidate.profileState())
                                        && candidate.expiresAt().isAfter(now))) {
            throw rejected(CAPABILITY_PROFILE_STALE);
        }
        if (candidates.stream()
                .noneMatch(
                        candidate ->
                                "CURRENT".equals(candidate.mappingState())
                                        && "CURRENT".equals(candidate.profileState())
                                        && candidate.regionAndPolicyMatch())) {
            throw rejected(UNSUPPORTED_REGION_OR_DATA_POLICY);
        }
        throw rejected(UNSUPPORTED_NARRATION_PACE);
    }

    private boolean eligibleForGeneration(UUID recipeId) {
        Boolean eligible =
                jdbcTemplate.queryForObject(
                        "SELECT "
                                + FROZEN_RECIPE_ELIGIBILITY_SQL
                                + """
                                FROM narration.generation_recipe gr
                                JOIN narration.voice_mapping vm ON vm.mapping_id = gr.voice_mapping_id
                                JOIN narration.provider_capability_profile p ON p.profile_id = gr.capability_profile_id
                                WHERE gr.recipe_id = ?
                                """,
                        Boolean.class,
                        Timestamp.from(identityClock.instant()),
                        recipeId);
        return Boolean.TRUE.equals(eligible);
    }

    private NarratorVoice narratorVoice(UUID voiceId) {
        List<NarratorVoice> voices =
                jdbcTemplate.query(
                        """
                        SELECT voice_id, display_name, english_variety, descriptor_primary,
                               descriptor_secondary, descriptor_review_version, availability,
                               preview_uri, preview_passage_version, preview_duration_seconds,
                               preview_ai_generated
                        FROM narration.narrator_voice WHERE voice_id = ?
                        """,
                        NARRATOR_VOICE_ROW_MAPPER,
                        voiceId);
        if (voices.isEmpty()) {
            throw rejected(UNISSUED_VOICE_IDENTIFIER);
        }
        return voices.getFirst();
    }

    private StoredConversion lockConversion(UUID listenerId, UUID conversionId) {
        List<StoredConversion> conversions =
                jdbcTemplate.query(
                        """
                        SELECT version, current_generation_recipe_id
                        FROM workflow.audiobook_conversion
                        WHERE conversion_id = ? AND listener_id = ? FOR UPDATE
                        """,
                        (resultSet, row) ->
                                new StoredConversion(
                                        resultSet.getLong("version"),
                                        resultSet.getObject(
                                                "current_generation_recipe_id", UUID.class)),
                        conversionId,
                        listenerId);
        if (conversions.isEmpty()) {
            throw rejected(AUDIOBOOK_CONVERSION_NOT_FOUND);
        }
        return conversions.getFirst();
    }

    private StoredOperation findOperation(String operationKey) {
        List<StoredOperation> operations =
                jdbcTemplate.query(
                        """
                        SELECT request_fingerprint, recipe_id
                        FROM narration.recipe_confirmation_operation WHERE operation_key = ?
                        """,
                        (resultSet, row) ->
                                new StoredOperation(
                                        resultSet.getString("request_fingerprint"),
                                        resultSet.getObject("recipe_id", UUID.class)),
                        operationKey);
        return operations.isEmpty() ? null : operations.getFirst();
    }

    private ConfirmedRecipe confirmedRecipe(UUID recipeId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT gr.recipe_id, gr.conversion_id, gr.narrator_voice_id, gr.voice_display_name,
                       gr.pace, gr.recipe_digest, ac.version
                FROM narration.generation_recipe gr
                JOIN workflow.audiobook_conversion ac ON ac.conversion_id = gr.conversion_id
                WHERE gr.recipe_id = ?
                """,
                (resultSet, row) ->
                        new ConfirmedRecipe(
                                resultSet.getObject("recipe_id", UUID.class),
                                resultSet.getObject("conversion_id", UUID.class),
                                resultSet.getObject("narrator_voice_id", UUID.class),
                                resultSet.getString("voice_display_name"),
                                NarrationPace.valueOf(resultSet.getString("pace")),
                                resultSet.getString("recipe_digest"),
                                resultSet.getLong("version")),
                recipeId);
    }

    private static void validate(ConfirmCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        Objects.requireNonNull(command.conversionId(), "conversionId");
        Objects.requireNonNull(command.voiceId(), "voiceId");
        Objects.requireNonNull(command.pace(), "pace");
        if (command.expectedConversionVersion() < 0) {
            throw new IllegalArgumentException("expectedConversionVersion must not be negative");
        }
        if (command.operationKey() == null
                || command.operationKey().isBlank()
                || command.operationKey().length() > 200) {
            throw new IllegalArgumentException(
                    "operationKey must be present and at most 200 characters");
        }
    }

    private static String fingerprint(ConfirmCommand command) {
        return sha256(
                """
                conversionId=%s
                voiceId=%s
                pace=%s
                expectedVersion=%d
                """
                        .formatted(
                                command.conversionId(),
                                command.voiceId(),
                                command.pace(),
                                command.expectedConversionVersion()));
    }

    private static String recipeDigest(
            UUID recipeId, ConfirmCommand command, EligibleMapping mapping, Instant createdAt) {
        return sha256(
                """
                schemaVersion=1
                recipeId=%s
                createdAt=%s
                conversionId=%s
                voiceId=%s
                pace=%s
                capabilityProfileId=%s
                capabilityProfileVersion=%s
                provider=%s
                service=%s
                endpoint=%s
                modelSnapshot=%s
                region=%s
                dataPolicyVersion=%s
                mappingId=%s
                mappingVersion=%s
                providerVoice=%s
                nativeControls=%s
                previewVersion=%s
                evaluationVersion=%s
                segmentationPolicyVersion=%s
                audioPolicyVersion=%s
                toolchainVersion=%s
                """
                        .formatted(
                                recipeId,
                                createdAt,
                                command.conversionId(),
                                command.voiceId(),
                                command.pace(),
                                mapping.profileId(),
                                mapping.profileVersion(),
                                mapping.provider(),
                                mapping.service(),
                                mapping.endpoint(),
                                mapping.modelSnapshot(),
                                mapping.region(),
                                mapping.dataPolicyVersion(),
                                mapping.mappingId(),
                                mapping.mappingVersion(),
                                mapping.providerVoice(),
                                mapping.nativeControls(),
                                mapping.previewVersion(),
                                mapping.evaluationVersion(),
                                SEGMENTATION_POLICY_VERSION,
                                AUDIO_POLICY_VERSION,
                                TOOLCHAIN_VERSION));
    }

    private static String sha256(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static NarrationSelectionRejectedException rejected(NarrationRejectionReason reason) {
        return new NarrationSelectionRejectedException(reason);
    }

    private record StoredOperation(String requestFingerprint, UUID recipeId) {}

    private record StoredConversion(long version, UUID currentRecipeId) {}

    private record ActiveRecipe(UUID recipeId, String recipeDigest, boolean eligible) {}

    private record StoredNarrationChoice(
            long version,
            UUID recipeId,
            UUID voiceId,
            String voiceDisplayName,
            NarrationPace pace) {}

    private record MappingCandidate(
            UUID mappingId,
            String mappingVersion,
            String providerVoice,
            String nativeControls,
            String requiredRegion,
            String requiredDataPolicyVersion,
            String previewVersion,
            String evaluationVersion,
            String mappingState,
            UUID profileId,
            String profileVersion,
            String provider,
            String service,
            String endpoint,
            String modelSnapshot,
            String region,
            String dataPolicyVersion,
            String profileState,
            String privacyState,
            String regionState,
            String accessState,
            String quotaState,
            String evaluationState,
            Instant expiresAt,
            boolean supportsPace) {

        boolean regionAndPolicyMatch() {
            return requiredRegion.equals(region)
                    && requiredDataPolicyVersion.equals(dataPolicyVersion);
        }

        boolean eligible(Instant now, String currentPreviewVersion) {
            return "CURRENT".equals(mappingState)
                    && "CURRENT".equals(profileState)
                    && "QUALIFIED".equals(privacyState)
                    && "QUALIFIED".equals(regionState)
                    && "QUALIFIED".equals(accessState)
                    && "QUALIFIED".equals(quotaState)
                    && "QUALIFIED".equals(evaluationState)
                    && expiresAt.isAfter(now)
                    && regionAndPolicyMatch()
                    && supportsPace
                    && nativeControls != null
                    && previewVersion.equals(currentPreviewVersion);
        }

        EligibleMapping eligibleMapping() {
            return new EligibleMapping(
                    mappingId,
                    mappingVersion,
                    providerVoice,
                    nativeControls,
                    previewVersion,
                    evaluationVersion,
                    profileId,
                    profileVersion,
                    provider,
                    service,
                    endpoint,
                    modelSnapshot,
                    region,
                    dataPolicyVersion);
        }
    }

    private record EligibleMapping(
            UUID mappingId,
            String mappingVersion,
            String providerVoice,
            String nativeControls,
            String previewVersion,
            String evaluationVersion,
            UUID profileId,
            String profileVersion,
            String provider,
            String service,
            String endpoint,
            String modelSnapshot,
            String region,
            String dataPolicyVersion) {}

    private record FailoverCandidate(
            UUID voiceId,
            String voiceDisplayName,
            String pace,
            UUID mappingId,
            String mappingVersion,
            String providerVoice,
            String nativeControls,
            String previewVersion,
            String evaluationVersion,
            UUID profileId,
            String profileVersion,
            String provider,
            String service,
            String endpoint,
            String model,
            String region,
            String dataPolicyVersion,
            String voiceEquivalenceVersion,
            String paceEquivalenceVersion,
            String segmentationPolicyVersion,
            String audioPolicyVersion,
            String toolchainVersion) {}
}
