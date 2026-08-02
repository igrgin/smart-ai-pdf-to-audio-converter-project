package dev.audiobook.platform.admission.internal.inspection;

import dev.audiobook.platform.admission.QuarantineObjectStore;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.admission.internal.inspection.InspectionWorkflowService;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class InspectionOutcomeRecordingServiceImpl implements InspectionOutcomeRecordingService {

    private final JdbcTemplate jdbcTemplate;
    private final QuarantineObjectStore objectStore;
    private final PublicationInspectionService publicationInspectionService;
    private final InspectionProperties inspectionProperties;
    private final Clock identityClock;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final PlatformIdentifierGenerator identifierGenerator;
    private final InspectionWorkflowService inspectionWorkflowService;

    @Override
    public Inspection inspect(InspectionCommand command) {
        Objects.requireNonNull(command, "command");
        String workerId = requiredReference(command.workerId(), "workerId");
        String operationKey = requiredReference(command.operationKey(), "operationKey");
        Instant now = identityClock.instant();
        if (command.leaseUntil() == null || !command.leaseUntil().isAfter(now)
                || command.leaseUntil().isAfter(now.plus(inspectionProperties.runtime()))) {
            throw new IllegalArgumentException("Inspection lease exceeds the configured runtime limit");
        }
        TransactionTemplate transactions = transactionTemplate();
        Claim claim = transactions.execute(status -> claim(command.workId(), workerId, command.leaseUntil(), operationKey));
        if (claim == null) {
            throw new IllegalStateException("Inspection claim transaction did not return a result");
        }
        if (claim.completed() != null) {
            return claim.completed();
        }
        if (!claim.claimed()) {
            return new Inspection(null, InspectionOutcome.LEASED_BY_ANOTHER_WORKER, null, false);
        }

        PublicationInspectionService.Result inspection;
        if (claim.retriesExhausted()) {
            inspection = PublicationInspectionService.Result.rejected("INSPECTION_RETRIES_EXHAUSTED");
        } else {
            try (var publication = objectStore.read(claim.subject().submissionId())) {
                inspection = publicationInspectionService.inspect(publication, claim.subject().declaredMediaType());
            } catch (IOException exception) {
                inspection = PublicationInspectionService.Result.rejected("INSPECTION_DEPENDENCY_FAILED");
            }
        }
        PublicationInspectionService.Result completedInspection = inspection;
        Inspection completed = transactions.execute(status -> completeInspection(
                claim.subject(), command.workId(), workerId, operationKey, completedInspection));
        if (completed == null) {
            throw new IllegalStateException("Inspection completion transaction did not return a result");
        }
        return completed;
    }

    private Claim claim(UUID workId, String workerId, Instant leaseUntil, String operationKey) {
        Objects.requireNonNull(workId, "workId");
        InspectionWorkflowService.Claim workflowClaim = inspectionWorkflowService.claim(
                workId, workerId, leaseUntil, operationKey);
        if (workflowClaim.status() == InspectionWorkflowService.ClaimStatus.COMPLETED) {
            return new Claim(null, false, false, loadInspection(workId, workerId, true));
        }
        if (workflowClaim.status() == InspectionWorkflowService.ClaimStatus.LEASED_BY_ANOTHER_WORKER) {
            return new Claim(null, false, false, null);
        }
        InspectionSubject subject = inspectionSubject(workId, workerId);
        return new Claim(
                subject,
                true,
                workflowClaim.status() == InspectionWorkflowService.ClaimStatus.RETRIES_EXHAUSTED,
                null);
    }

    private Inspection completeInspection(
            InspectionSubject subject,
            UUID workId,
            String workerId,
            String operationKey,
            PublicationInspectionService.Result inspection) {
        Instant now = identityClock.instant();
        InspectionOutcome outcome = inspection.accepted() ? InspectionOutcome.ADMITTED : InspectionOutcome.REJECTED;
        Boolean recorded = jdbcTemplate.queryForObject(
                "SELECT admission.record_inspection_result(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                Boolean.class,
                identifierGenerator.generate(),
                workId,
                workerId,
                operationKey,
                outcome.name(),
                inspection.reasonCode(),
                inspection.accepted() ? inspection.mediaType() : null,
                inspection.accepted() ? inspection.toolchainVersion() : "inspection-gates-v2",
                databaseTime(now));
        if (!Boolean.TRUE.equals(recorded)) {
            return loadInspection(workId, workerId, true);
        }
        return new Inspection(subject.submissionId(), outcome, inspection.reasonCode(), false);
    }

    private InspectionSubject inspectionSubject(UUID workId, String workerId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT submission_id, listener_id, declared_media_type
                FROM admission.inspection_subject(?, ?)
                """,
                (resultSet, row) -> new InspectionSubject(
                        resultSet.getObject("submission_id", UUID.class),
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("declared_media_type")),
                workId,
                workerId);
    }

    private Inspection loadInspection(UUID workId, String workerId, boolean replayed) {
        return jdbcTemplate.queryForObject(
                """
                SELECT submission_id, outcome, reason_code
                FROM admission.load_inspection_result(?, ?)
                """,
                (resultSet, row) -> new Inspection(
                        resultSet.getObject("submission_id", UUID.class),
                        InspectionOutcome.valueOf(resultSet.getString("outcome")),
                        resultSet.getString("reason_code"),
                        replayed),
                workId,
                workerId);
    }

    private TransactionTemplate transactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private static String requiredReference(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException(field + " must contain between 1 and 200 characters");
        }
        return value;
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record InspectionSubject(UUID submissionId, UUID listenerId, String declaredMediaType) {
    }

    private record Claim(InspectionSubject subject, boolean claimed, boolean retriesExhausted, Inspection completed) {
    }
}
