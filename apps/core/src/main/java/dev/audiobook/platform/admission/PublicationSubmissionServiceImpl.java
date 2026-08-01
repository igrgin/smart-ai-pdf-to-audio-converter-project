package dev.audiobook.platform.admission;

import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.InspectionWorkflowService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PublicationSubmissionServiceImpl implements PublicationSubmissionService {

    private static final String EPUB_MEDIA_TYPE = "application/epub+zip";
    private static final String PDF_MEDIA_TYPE = "application/pdf";

    private final JdbcTemplate jdbcTemplate;
    private final ConversionEntitlementService entitlementService;
    private final QuarantineObjectStore objectStore;
    private final AdmissionProperties properties;
    private final Clock identityClock;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final AudiobookConversionService audiobookConversionService;
    private final InspectionWorkflowService inspectionWorkflowService;

    @Override
    @Transactional
    public Creation create(CreateCommand command) {
        ValidatedCreate request = validate(command);
        StoredOperation replay = findOperation(request.idempotencyKey());
        if (replay != null) {
            verifyReplay(replay, "CREATE", request.fingerprint());
            return loadCreation(replay.submissionId(), false);
        }

        Instant now = identityClock.instant();
        UUID submissionId = identifierGenerator.generate();
        UUID attestationId = identifierGenerator.generate();
        UUID conversionId = identifierGenerator.generate();
        UUID sessionId = identifierGenerator.generate();
        Instant expiresAt = now.plus(properties.uploadSessionValidity());
        ConversionEntitlementService.AdmissionDecision reservation = entitlementService.authorizeSpeech(
                new ConversionEntitlementService.AdmissionRequest(
                        request.listenerId(),
                        conversionId,
                        properties.provider(),
                        "preinspection-v1",
                        properties.rateCardVersion(),
                        properties.reservedCharacters(),
                        properties.reservedProviderCostMicros(),
                        "submission-reserve:" + submissionId));
        if (!reservation.authorized()) {
            throw new SubmissionRejectedException("ENTITLEMENT_" + reservation.denial().name());
        }
        String token = capability(sessionId, submissionId, expiresAt);

        jdbcTemplate.update(
                """
                INSERT INTO rights_attestation (
                    attestation_id, listener_id, terms_version, notice_version, submitted_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                attestationId,
                request.listenerId(),
                request.termsVersion(),
                request.noticeVersion(),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO publication_submission (
                    submission_id, listener_id, attestation_id, entitlement_reservation_id,
                    planned_conversion_id, state, declared_media_type, declared_byte_length,
                    declared_sha256, upload_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'AWAITING_UPLOAD', ?, ?, ?, ?, ?, ?)
                """,
                submissionId,
                request.listenerId(),
                attestationId,
                reservation.reservationId(),
                conversionId,
                request.mediaType(),
                request.byteLength(),
                request.sha256(),
                databaseTime(expiresAt),
                databaseTime(now),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO upload_session (
                    session_id, submission_id, capability_hash, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                sessionId,
                submissionId,
                sha256(token),
                databaseTime(expiresAt),
                databaseTime(now));
        saveOperation(request.idempotencyKey(), "CREATE", request.fingerprint(), submissionId, now);
        audit(request.listenerId(), submissionId, "PUBLICATION_SUBMISSION_CREATED", "AWAITING_UPLOAD", null, now);
        return new Creation(
                submissionId,
                SubmissionState.AWAITING_UPLOAD,
                new UploadSession(token, expiresAt, properties.uploadChunkBytes()),
                true);
    }

    @Override
    @Transactional
    public UploadProgress upload(UploadCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.submissionId(), "submissionId");
        byte[] bytes = command.bytes();
        if (bytes.length == 0 || bytes.length > properties.uploadChunkBytes()) {
            throw new IllegalArgumentException("Upload chunk length is outside the allowed range");
        }
        String chunkDigest = requiredSha256(command.chunkSha256(), "chunkSha256");
        if (!MessageDigest.isEqual(
                sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                chunkDigest.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Upload chunk SHA-256 does not match its bytes");
        }
        StoredUpload upload = lockUpload(command.submissionId());
        verifyCapability(upload, command.token());
        Instant now = identityClock.instant();
        if (!now.isBefore(upload.expiresAt())) {
            throw new SubmissionRejectedException("UPLOAD_SESSION_EXPIRED");
        }
        if (upload.state() != SubmissionState.AWAITING_UPLOAD) {
            throw new SubmissionRejectedException("INVALID_SUBMISSION_STATE");
        }
        if (command.totalBytes() != upload.declaredByteLength()) {
            throw new SubmissionRejectedException("UPLOAD_SIZE_MISMATCH");
        }
        if (command.offset() < upload.nextOffset()) {
            StoredChunk existing = findChunk(command.submissionId(), command.offset());
            if (existing == null || existing.byteLength() != bytes.length || !existing.sha256().equals(chunkDigest)) {
                throw new SubmissionRejectedException("UPLOAD_REPLAY_CONFLICT");
            }
            return new UploadProgress(upload.nextOffset(), upload.storageGeneration() != null, upload.storageGeneration());
        }
        if (command.offset() != upload.nextOffset()
                || command.offset() + bytes.length > upload.declaredByteLength()) {
            throw new SubmissionRejectedException("UPLOAD_OFFSET_MISMATCH");
        }

        long nextOffset = command.offset() + bytes.length;
        boolean complete = nextOffset == upload.declaredByteLength();
        QuarantineObjectStore.StoredObject stored;
        try {
            stored = objectStore.append(command.submissionId(), command.offset(), bytes, complete);
        } catch (IOException exception) {
            throw new IllegalStateException("Quarantine storage is unavailable", exception);
        }
        jdbcTemplate.update(
                """
                INSERT INTO upload_chunk (submission_id, chunk_offset, byte_length, sha256, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                command.submissionId(),
                command.offset(),
                bytes.length,
                chunkDigest,
                databaseTime(now));
        jdbcTemplate.update(
                "UPDATE upload_session SET next_offset = ?, storage_generation = ? WHERE submission_id = ?",
                nextOffset,
                stored.generation(),
                command.submissionId());
        if (complete) {
            jdbcTemplate.update(
                    """
                    INSERT INTO quarantine_object (
                        object_id, listener_id, submission_id, object_key, storage_generation,
                        byte_length, sha256, cleanup_due_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.submissionId(),
                    upload.listenerId(),
                    command.submissionId(),
                    stored.key(),
                    stored.generation(),
                    stored.byteLength(),
                    stored.sha256(),
                    databaseTime(now.plus(properties.quarantineRetention())),
                    databaseTime(now));
        }
        return new UploadProgress(nextOffset, complete, stored.generation());
    }

    @Override
    @Transactional
    public Submission confirm(ConfirmCommand command) {
        Objects.requireNonNull(command, "command");
        String operationKey = requiredReference(command.idempotencyKey(), "idempotencyKey");
        String requestDigest = requiredSha256(command.sha256(), "sha256");
        String fingerprint = fingerprint(
                command.listenerId(), command.submissionId(), command.storageGeneration(),
                command.byteLength(), requestDigest);
        StoredOperation replay = findOperation(operationKey);
        if (replay != null) {
            verifyReplay(replay, "CONFIRM", fingerprint);
            return submission(command.listenerId(), replay.submissionId());
        }
        StoredSubmission stored = lockSubmission(command.submissionId());
        verifyOwner(stored, command.listenerId());
        if (stored.state() != SubmissionState.AWAITING_UPLOAD) {
            throw new SubmissionRejectedException("INVALID_SUBMISSION_STATE");
        }
        QuarantineObjectStore.StoredObject object;
        try {
            object = objectStore.inspect(command.submissionId());
        } catch (IOException | IllegalStateException exception) {
            return rejectUpload(stored, operationKey, fingerprint, "UPLOAD_MISMATCH");
        }
        boolean matches = Objects.equals(command.storageGeneration(), stored.storageGeneration())
                && Objects.equals(command.storageGeneration(), object.generation())
                && command.byteLength() == stored.declaredByteLength()
                && command.byteLength() == object.byteLength()
                && requestDigest.equals(stored.declaredSha256())
                && requestDigest.equals(object.sha256());
        if (!matches) {
            return rejectUpload(stored, operationKey, fingerprint, "UPLOAD_MISMATCH");
        }

        Instant now = identityClock.instant();
        inspectionWorkflowService.schedule(stored.submissionId(), stored.listenerId());
        jdbcTemplate.update(
                """
                UPDATE publication_submission
                SET state = 'UPLOADED', version = version + 1, updated_at = ?
                WHERE submission_id = ?
                """,
                databaseTime(now),
                stored.submissionId());
        saveOperation(operationKey, "CONFIRM", fingerprint, stored.submissionId(), now);
        audit(stored.listenerId(), stored.submissionId(), "QUARANTINE_UPLOAD_CONFIRMED", "UPLOADED", null, now);
        return new Submission(stored.submissionId(), SubmissionState.UPLOADED, null, null);
    }

    @Override
    @Transactional
    public Submission cancel(CancelCommand command) {
        Objects.requireNonNull(command, "command");
        String operationKey = requiredReference(command.idempotencyKey(), "idempotencyKey");
        String fingerprint = fingerprint(command.listenerId(), command.submissionId());
        StoredOperation replay = findOperation(operationKey);
        if (replay != null) {
            verifyReplay(replay, "CANCEL", fingerprint);
            return submission(command.listenerId(), replay.submissionId());
        }
        StoredSubmission stored = lockSubmission(command.submissionId());
        verifyOwner(stored, command.listenerId());
        if (stored.state() == SubmissionState.ADMITTED) {
            throw new SubmissionRejectedException("SUBMISSION_ALREADY_ADMITTED");
        }
        if (terminal(stored.state())) {
            throw new SubmissionRejectedException("INVALID_SUBMISSION_STATE");
        }
        return terminate(stored, SubmissionState.CANCELLED, "LISTENER_CANCELLED", operationKey, "CANCEL", fingerprint);
    }

    @Override
    public int expireDue() {
        List<UUID> due = jdbcTemplate.queryForList(
                """
                SELECT submission_id FROM publication_submission
                WHERE state = 'AWAITING_UPLOAD' AND upload_expires_at <= ?
                ORDER BY submission_id
                """,
                UUID.class,
                databaseTime(identityClock.instant()));
        TransactionTemplate transactions = transactionTemplate();
        int expired = 0;
        for (UUID submissionId : due) {
            Boolean changed = transactions.execute(status -> {
                StoredSubmission stored = lockSubmission(submissionId);
                if (stored.state() != SubmissionState.AWAITING_UPLOAD) {
                    return false;
                }
                terminate(
                        stored,
                        SubmissionState.EXPIRED,
                        "UPLOAD_SESSION_EXPIRED",
                        "expire:" + submissionId,
                        "EXPIRE",
                        fingerprint(submissionId));
                return true;
            });
            if (Boolean.TRUE.equals(changed)) {
                expired++;
            }
        }
        return expired;
    }

    @Override
    public int applyInspectionResults() {
        List<UUID> pending = jdbcTemplate.queryForList(
                """
                SELECT r.work_id
                FROM inspection_result r
                JOIN publication_submission p ON p.submission_id = r.submission_id
                WHERE p.state = 'UPLOADED'
                ORDER BY r.created_at, r.work_id
                LIMIT 10
                """,
                UUID.class);
        TransactionTemplate transactions = transactionTemplate();
        int applied = 0;
        for (UUID workId : pending) {
            Boolean changed = transactions.execute(status -> applyInspectionResult(workId));
            if (Boolean.TRUE.equals(changed)) {
                applied++;
            }
        }
        return applied;
    }

    @Override
    public Submission submission(UUID listenerId, UUID submissionId) {
        Objects.requireNonNull(listenerId, "listenerId");
        StoredSubmission stored = findSubmission(submissionId, false);
        verifyOwner(stored, listenerId);
        return new Submission(stored.submissionId(), stored.state(), stored.reasonCode(), stored.admittedConversionId());
    }

    private boolean applyInspectionResult(UUID workId) {
        StoredInspectionResult result = jdbcTemplate.queryForObject(
                """
                SELECT r.submission_id, r.outcome, r.reason_code, r.media_type
                FROM inspection_result r WHERE r.work_id = ?
                """,
                (resultSet, row) -> new StoredInspectionResult(
                        resultSet.getObject("submission_id", UUID.class),
                        InspectionOutcomeRecordingService.InspectionOutcome.valueOf(resultSet.getString("outcome")),
                        resultSet.getString("reason_code"),
                        resultSet.getString("media_type")),
                workId);
        StoredSubmission stored = lockSubmission(result.submissionId());
        if (stored.state() != SubmissionState.UPLOADED) {
            return false;
        }
        Instant now = identityClock.instant();
        if (result.outcome() == InspectionOutcomeRecordingService.InspectionOutcome.ADMITTED) {
            UUID sourceId = identifierGenerator.generate();
            jdbcTemplate.update(
                    """
                    INSERT INTO source_publication (
                        source_publication_id, listener_id, submission_id, media_type, byte_length, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    sourceId,
                    stored.listenerId(),
                    stored.submissionId(),
                    result.mediaType(),
                    stored.declaredByteLength(),
                    databaseTime(now));
            audiobookConversionService.createPreparing(stored.plannedConversionId(), stored.listenerId(), sourceId);
            jdbcTemplate.update(
                    """
                    UPDATE publication_submission
                    SET state = 'ADMITTED', version = version + 1, updated_at = ?
                    WHERE submission_id = ?
                    """,
                    databaseTime(now),
                    stored.submissionId());
            audit(stored.listenerId(), stored.submissionId(), "SOURCE_PUBLICATION_ADMITTED", "ADMITTED", null, now);
        } else {
            releaseReservation(stored);
            jdbcTemplate.update(
                    """
                    UPDATE publication_submission
                    SET state = 'REJECTED', reason_code = ?, version = version + 1, updated_at = ?
                    WHERE submission_id = ?
                    """,
                    result.reasonCode(),
                    databaseTime(now),
                    stored.submissionId());
            createCleanup(stored.submissionId(), result.reasonCode(), now);
            audit(stored.listenerId(), stored.submissionId(),
                    "PUBLICATION_INSPECTION", "REJECTED", result.reasonCode(), now);
        }
        return true;
    }

    private Submission rejectUpload(
            StoredSubmission stored, String operationKey, String fingerprint, String reason) {
        return terminate(stored, SubmissionState.REJECTED, reason, operationKey, "CONFIRM", fingerprint);
    }

    private Submission terminate(
            StoredSubmission stored,
            SubmissionState state,
            String reason,
            String operationKey,
            String operationType,
            String fingerprint) {
        Instant now = identityClock.instant();
        releaseReservation(stored);
        jdbcTemplate.update(
                """
                UPDATE publication_submission
                SET state = ?, reason_code = ?, version = version + 1, updated_at = ?
                WHERE submission_id = ?
                """,
                state.name(),
                reason,
                databaseTime(now),
                stored.submissionId());
        createCleanup(stored.submissionId(), reason, now);
        saveOperation(operationKey, operationType, fingerprint, stored.submissionId(), now);
        audit(stored.listenerId(), stored.submissionId(), "PUBLICATION_SUBMISSION_TERMINATED", state.name(), reason, now);
        return new Submission(stored.submissionId(), state, reason, null);
    }

    private void releaseReservation(StoredSubmission stored) {
        entitlementService.settle(new ConversionEntitlementService.SettlementRequest(
                stored.reservationId(),
                0,
                0,
                "submission-release:" + stored.submissionId()));
    }

    private void createCleanup(UUID submissionId, String reason, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO cleanup_obligation (
                    obligation_id, submission_id, object_id, reason_code, due_at, created_at
                ) VALUES (?, ?, (SELECT object_id FROM quarantine_object WHERE submission_id = ?), ?, ?, ?)
                ON CONFLICT (submission_id) DO NOTHING
                """,
                identifierGenerator.generate(),
                submissionId,
                submissionId,
                reason,
                databaseTime(now),
                databaseTime(now));
    }

    private Creation loadCreation(UUID submissionId, boolean created) {
        return jdbcTemplate.queryForObject(
                """
                SELECT s.submission_id, s.state, u.session_id, u.expires_at
                FROM publication_submission s JOIN upload_session u ON u.submission_id = s.submission_id
                WHERE s.submission_id = ?
                """,
                (resultSet, row) -> {
                    UUID sessionId = resultSet.getObject("session_id", UUID.class);
                    Instant expiresAt = resultSet.getObject("expires_at", OffsetDateTime.class).toInstant();
                    return new Creation(
                            submissionId,
                            SubmissionState.valueOf(resultSet.getString("state")),
                            new UploadSession(
                                    capability(sessionId, submissionId, expiresAt),
                                    expiresAt,
                                    properties.uploadChunkBytes()),
                            created);
                },
                submissionId);
    }

    private StoredUpload lockUpload(UUID submissionId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT s.submission_id, s.listener_id, s.state, s.declared_byte_length,
                           u.session_id, u.capability_hash, u.next_offset,
                           u.storage_generation, u.expires_at
                    FROM publication_submission s
                    JOIN upload_session u ON u.submission_id = s.submission_id
                    WHERE s.submission_id = ? FOR UPDATE OF u, s
                    """,
                    (resultSet, row) -> new StoredUpload(
                            resultSet.getObject("submission_id", UUID.class),
                            resultSet.getObject("listener_id", UUID.class),
                            SubmissionState.valueOf(resultSet.getString("state")),
                            resultSet.getLong("declared_byte_length"),
                            resultSet.getObject("session_id", UUID.class),
                            resultSet.getString("capability_hash"),
                            resultSet.getLong("next_offset"),
                            resultSet.getString("storage_generation"),
                            resultSet.getObject("expires_at", OffsetDateTime.class).toInstant()),
                    submissionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new SubmissionRejectedException("UNKNOWN_UPLOAD_SESSION");
        }
    }

    private StoredSubmission lockSubmission(UUID submissionId) {
        return findSubmission(submissionId, true);
    }

    private StoredSubmission findSubmission(UUID submissionId, boolean lock) {
        Objects.requireNonNull(submissionId, "submissionId");
        String suffix = lock ? " FOR UPDATE OF s" : "";
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT s.submission_id, s.listener_id, s.entitlement_reservation_id,
                           s.planned_conversion_id, s.state, s.declared_media_type, s.declared_byte_length,
                           s.declared_sha256, s.reason_code, u.storage_generation,
                           c.conversion_id AS admitted_conversion_id
                    FROM publication_submission s
                    JOIN upload_session u ON u.submission_id = s.submission_id
                    LEFT JOIN source_publication p ON p.submission_id = s.submission_id
                    LEFT JOIN audiobook_conversion c ON c.source_publication_id = p.source_publication_id
                    WHERE s.submission_id = ?
                    """ + suffix,
                    (resultSet, row) -> new StoredSubmission(
                            resultSet.getObject("submission_id", UUID.class),
                            resultSet.getObject("listener_id", UUID.class),
                            resultSet.getObject("entitlement_reservation_id", UUID.class),
                            resultSet.getObject("planned_conversion_id", UUID.class),
                            SubmissionState.valueOf(resultSet.getString("state")),
                            resultSet.getString("declared_media_type"),
                            resultSet.getLong("declared_byte_length"),
                            resultSet.getString("declared_sha256"),
                            resultSet.getString("reason_code"),
                            resultSet.getString("storage_generation"),
                            resultSet.getObject("admitted_conversion_id", UUID.class)),
                    submissionId);
        } catch (EmptyResultDataAccessException exception) {
            throw new SubmissionRejectedException("UNKNOWN_SUBMISSION");
        }
    }

    private StoredChunk findChunk(UUID submissionId, long offset) {
        return jdbcTemplate.query(
                "SELECT byte_length, sha256 FROM upload_chunk WHERE submission_id = ? AND chunk_offset = ?",
                resultSet -> resultSet.next()
                        ? new StoredChunk(resultSet.getInt("byte_length"), resultSet.getString("sha256"))
                        : null,
                submissionId,
                offset);
    }

    private StoredOperation findOperation(String operationKey) {
        return jdbcTemplate.query(
                """
                SELECT operation_type, request_fingerprint, submission_id
                FROM submission_operation WHERE operation_key = ?
                """,
                resultSet -> resultSet.next()
                        ? new StoredOperation(
                                resultSet.getString("operation_type"),
                                resultSet.getString("request_fingerprint"),
                                resultSet.getObject("submission_id", UUID.class))
                        : null,
                operationKey);
    }

    private void saveOperation(
            String operationKey, String operationType, String fingerprint, UUID submissionId, Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO submission_operation (
                    operation_key, operation_type, request_fingerprint, submission_id, created_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                operationKey,
                operationType,
                fingerprint,
                submissionId,
                databaseTime(now));
    }

    private static void verifyReplay(StoredOperation operation, String type, String fingerprint) {
        if (!operation.operationType().equals(type) || !operation.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException("Idempotency key was already used for a different command");
        }
    }

    private void verifyCapability(StoredUpload upload, String token) {
        String supplied = sha256(requiredReference(token, "uploadToken"));
        if (!MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.US_ASCII),
                upload.capabilityHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new SubmissionRejectedException("INVALID_UPLOAD_CAPABILITY");
        }
        String expected = capability(upload.sessionId(), upload.submissionId(), upload.expiresAt());
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new SubmissionRejectedException("INVALID_UPLOAD_CAPABILITY");
        }
    }

    private String capability(UUID sessionId, UUID submissionId, Instant expiresAt) {
        String payload = sessionId + "." + submissionId + "." + expiresAt.getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.uploadTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            return payload + "." + signature;
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Upload capability signing is unavailable", exception);
        }
    }

    private ValidatedCreate validate(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.listenerId(), "listenerId");
        String mediaType = requiredReference(command.mediaType(), "mediaType").toLowerCase(Locale.ROOT);
        if (!EPUB_MEDIA_TYPE.equals(mediaType) && !PDF_MEDIA_TYPE.equals(mediaType)) {
            throw new IllegalArgumentException("Only application/pdf and application/epub+zip are accepted");
        }
        if (command.byteLength() <= 0 || command.byteLength() > properties.maximumUploadBytes()) {
            throw new IllegalArgumentException("Publication byte length is outside the allowed range");
        }
        String digest = requiredSha256(command.sha256(), "sha256");
        String terms = requiredReference(command.termsVersion(), "termsVersion");
        String notice = requiredReference(command.noticeVersion(), "noticeVersion");
        if (!properties.rightsTermsVersion().equals(terms) || !properties.rightsNoticeVersion().equals(notice)) {
            throw new IllegalArgumentException("Rights Attestation version is not current");
        }
        String operation = requiredReference(command.idempotencyKey(), "idempotencyKey");
        return new ValidatedCreate(
                command.listenerId(),
                mediaType,
                command.byteLength(),
                digest,
                terms,
                notice,
                operation,
                fingerprint(command.listenerId(), mediaType, command.byteLength(), digest, terms, notice));
    }

    private static String requiredReference(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(field + " must contain between 1 and 200 characters");
        }
        return value;
    }

    private static String requiredSha256(String value, String field) {
        String normalized = requiredReference(value, field).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    private static void verifyOwner(StoredSubmission submission, UUID listenerId) {
        if (!submission.listenerId().equals(listenerId)) {
            throw new SubmissionRejectedException("UNKNOWN_SUBMISSION");
        }
    }

    private static boolean terminal(SubmissionState state) {
        return state == SubmissionState.REJECTED
                || state == SubmissionState.EXPIRED
                || state == SubmissionState.CANCELLED;
    }

    private void audit(
            UUID listenerId,
            UUID submissionId,
            String eventType,
            String decision,
            String reason,
            Instant now) {
        jdbcTemplate.update(
                """
                INSERT INTO admission_audit_event (
                    event_id, listener_id, submission_id, event_type, decision, reason_code, occurred_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                identifierGenerator.generate(),
                listenerId,
                submissionId,
                eventType,
                decision,
                reason,
                databaseTime(now));
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String fingerprint(Object... values) {
        return sha256(java.util.Arrays.stream(values).map(String::valueOf).collect(java.util.stream.Collectors.joining("\u001f")));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record ValidatedCreate(
            UUID listenerId,
            String mediaType,
            long byteLength,
            String sha256,
            String termsVersion,
            String noticeVersion,
            String idempotencyKey,
            String fingerprint) {
    }

    private record StoredOperation(String operationType, String fingerprint, UUID submissionId) {
    }

    private record StoredUpload(
            UUID submissionId,
            UUID listenerId,
            SubmissionState state,
            long declaredByteLength,
            UUID sessionId,
            String capabilityHash,
            long nextOffset,
            String storageGeneration,
            Instant expiresAt) {
    }

    private record StoredChunk(int byteLength, String sha256) {
    }

    private record StoredSubmission(
            UUID submissionId,
            UUID listenerId,
            UUID reservationId,
            UUID plannedConversionId,
            SubmissionState state,
            String declaredMediaType,
            long declaredByteLength,
            String declaredSha256,
            String reasonCode,
            String storageGeneration,
            UUID admittedConversionId) {
    }

    private record StoredInspectionResult(
            UUID submissionId,
            InspectionOutcomeRecordingService.InspectionOutcome outcome,
            String reasonCode,
            String mediaType) {
    }

    public static class SubmissionRejectedException extends RuntimeException {
        private final String reasonCode;

        public SubmissionRejectedException(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
