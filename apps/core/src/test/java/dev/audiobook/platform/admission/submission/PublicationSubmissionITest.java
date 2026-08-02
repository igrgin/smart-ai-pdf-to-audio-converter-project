package dev.audiobook.platform.admission.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.*;
import dev.audiobook.platform.admission.inspection.dispatch.InspectionWorkPublisher;
import dev.audiobook.platform.admission.inspection.dispatch.service.AdmissionOutboxRelayService;
import dev.audiobook.platform.admission.inspection.toolchain.malware.MalwareScanner;
import dev.audiobook.platform.admission.inspection.toolchain.pdf.service.QpdfValidationService;
import dev.audiobook.platform.admission.inspection.work.service.InspectionOutcomeRecordingService;
import dev.audiobook.platform.admission.inspection.work.service.InspectionWorkerService;
import dev.audiobook.platform.admission.inspection.work.service.InspectionWorkflowService;
import dev.audiobook.platform.admission.submission.service.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ledger.service.ConversionEntitlementService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.identity.signin.ExternalIdentity;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class PublicationSubmissionITest {

    private final PublicationSubmissionService submissionService;
    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;
    private final JdbcTemplate jdbcTemplate;
    private final AdmissionOutboxRelayService outboxRelayService;
    private final AudiobookConversionService audiobookConversionService;
    private final InspectionWorkflowService inspectionWorkflowService;
    private final InspectionWorkerService inspectionWorkerService;
    private final InspectionOutcomeRecordingService inspectionOutcomeRecordingService;

    @MockitoBean private InspectionWorkPublisher inspectionWorkPublisher;

    @MockitoBean private MalwareScanner malwareScanner;

    @MockitoBean private QpdfValidationService qpdfValidationService;

    @Autowired
    PublicationSubmissionITest(
            PublicationSubmissionService submissionService,
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            JdbcTemplate jdbcTemplate,
            AdmissionOutboxRelayService outboxRelayService,
            AudiobookConversionService audiobookConversionService,
            InspectionWorkflowService inspectionWorkflowService,
            InspectionWorkerService inspectionWorkerService,
            InspectionOutcomeRecordingService inspectionOutcomeRecordingService) {
        this.submissionService = submissionService;
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRelayService = outboxRelayService;
        this.audiobookConversionService = audiobookConversionService;
        this.inspectionWorkflowService = inspectionWorkflowService;
        this.inspectionWorkerService = inspectionWorkerService;
        this.inspectionOutcomeRecordingService = inspectionOutcomeRecordingService;
    }

    @BeforeEach
    void cleanSystemBoundaries() {
        org.mockito.Mockito.when(malwareScanner.scan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(MalwareScanner.Result.CLEAN);
        org.mockito.Mockito.when(qpdfValidationService.validate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(QpdfValidationService.Result.VALID);
    }

    @Test
    void authorizedDrmFreeEnglishEpubMovesFromQuarantineToPreparingExactlyOnce() throws Exception {
        UUID listenerId = entitledListener("accepted");
        byte[] epub = validEnglishEpub();
        String digest = sha256(epub);

        PublicationSubmissionService.Creation creation =
                submissionService.create(
                        createCommand(listenerId, epub.length, digest, "accepted-create"));
        PublicationSubmissionService.Creation replay =
                submissionService.create(
                        createCommand(listenerId, epub.length, digest, "accepted-create"));

        assertThat(creation.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.submissionId()).isEqualTo(creation.submissionId());
        assertThat(entitlementService.allowance(listenerId).reservedCharacters())
                .isEqualTo(500_000);

        int split = epub.length / 2;
        byte[] first = java.util.Arrays.copyOfRange(epub, 0, split);
        byte[] second = java.util.Arrays.copyOfRange(epub, split, epub.length);
        PublicationSubmissionService.UploadProgress partial =
                submissionService.upload(upload(creation, 0, epub.length, first));
        PublicationSubmissionService.UploadProgress uploaded =
                submissionService.upload(upload(creation, split, epub.length, second));

        assertThat(partial.complete()).isFalse();
        assertThat(partial.nextOffset()).isEqualTo(split);
        assertThat(uploaded.complete()).isTrue();
        assertThat(uploaded.storageGeneration()).isNotBlank();

        PublicationSubmissionService.Submission confirmed =
                submissionService.confirm(
                        new PublicationSubmissionService.ConfirmCommand(
                                listenerId,
                                creation.submissionId(),
                                uploaded.storageGeneration(),
                                epub.length,
                                digest,
                                "accepted-confirm"));
        UUID workId =
                jdbcTemplate.queryForObject(
                        "SELECT work_id FROM inspection_work WHERE submission_id = ?",
                        UUID.class,
                        creation.submissionId());
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE aggregate_id = ? AND"
                                + " message_type = 'INSPECT_SUBMISSION'",
                        UUID.class,
                        creation.submissionId());

        assertThat(confirmed.state())
                .isEqualTo(PublicationSubmissionService.SubmissionState.UPLOADED);
        assertThat(outboxRelayService.relayPending()).isEqualTo(1);
        org.mockito.Mockito.verify(inspectionWorkPublisher).publish(messageId, workId);
        assertThat(outboxRelayService.relayPending()).isZero();
        assertThat(inspectionWorkflowService.acceptDelivery(messageId, workId).duplicate())
                .isFalse();
        assertThat(inspectionWorkflowService.acceptDelivery(messageId, workId).duplicate())
                .isTrue();

        InspectionOutcomeRecordingService.Inspection inspected =
                inspectionOutcomeRecordingService.inspect(
                        new InspectionOutcomeRecordingService.InspectionCommand(
                                workId,
                                "inspection-worker-1",
                                Instant.now().plusSeconds(60),
                                "inspect-" + workId));
        InspectionOutcomeRecordingService.Inspection inspectedReplay =
                inspectionOutcomeRecordingService.inspect(
                        new InspectionOutcomeRecordingService.InspectionCommand(
                                workId,
                                "inspection-worker-1",
                                Instant.now().plusSeconds(60),
                                "inspect-" + workId));

        assertThat(inspected.outcome())
                .isEqualTo(InspectionOutcomeRecordingService.InspectionOutcome.ADMITTED);
        assertThat(inspectedReplay.replayed()).isTrue();
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);
        assertThat(submissionService.submission(listenerId, creation.submissionId()).state())
                .isEqualTo(PublicationSubmissionService.SubmissionState.ADMITTED);
        UUID conversionId =
                jdbcTemplate.queryForObject(
                        "SELECT planned_conversion_id FROM publication_submission WHERE"
                                + " submission_id = ?",
                        UUID.class,
                        creation.submissionId());
        assertThat(audiobookConversionService.conversions(listenerId))
                .containsExactly(
                        new AudiobookConversionService.AudiobookConversion(
                                conversionId,
                                AudiobookConversionService.ConversionState.PREPARING,
                                "NARRATION_PLAN_PENDING",
                                java.util.List.of(),
                                0));
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM source_publication WHERE submission_id = ?",
                                Long.class,
                                creation.submissionId()))
                .isEqualTo(1L);
    }

    @Test
    void failedOutboxPublishRemainsPendingForSafeRedelivery() throws Exception {
        UUID listenerId = entitledListener("outbox-retry");
        byte[] epub = validEnglishEpub();
        String digest = sha256(epub);
        PublicationSubmissionService.Creation creation =
                submissionService.create(
                        createCommand(listenerId, epub.length, digest, "outbox-create"));
        PublicationSubmissionService.UploadProgress uploaded =
                submissionService.upload(upload(creation, 0, epub.length, epub));
        submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        listenerId,
                        creation.submissionId(),
                        uploaded.storageGeneration(),
                        epub.length,
                        digest,
                        "outbox-confirm"));
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE aggregate_id = ?",
                        UUID.class,
                        creation.submissionId());
        org.mockito.Mockito.doThrow(new IllegalStateException("queue unavailable"))
                .when(inspectionWorkPublisher)
                .publish(
                        org.mockito.ArgumentMatchers.eq(messageId),
                        org.mockito.ArgumentMatchers.any(UUID.class));

        assertThatThrownBy(outboxRelayService::relayPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("queue unavailable");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_at IS NULL FROM admission_outbox WHERE message_id"
                                        + " = ?",
                                Boolean.class,
                                messageId))
                .isTrue();
    }

    @Test
    void uploadMismatchReleasesReservationsAndCreatesCleanupWithoutAdmittingPublication()
            throws Exception {
        UUID listenerId = entitledListener("mismatch");
        byte[] epub = validEnglishEpub();
        PublicationSubmissionService.Creation creation =
                submissionService.create(
                        createCommand(listenerId, epub.length, sha256(epub), "mismatch-create"));
        PublicationSubmissionService.UploadProgress uploaded =
                submissionService.upload(upload(creation, 0, epub.length, epub));

        PublicationSubmissionService.Submission result =
                submissionService.confirm(
                        new PublicationSubmissionService.ConfirmCommand(
                                listenerId,
                                creation.submissionId(),
                                uploaded.storageGeneration(),
                                epub.length,
                                "b".repeat(64),
                                "mismatch-confirm"));

        assertThat(result.state()).isEqualTo(PublicationSubmissionService.SubmissionState.REJECTED);
        assertThat(result.reasonCode()).isEqualTo("UPLOAD_MISMATCH");
        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters)
                .containsExactly(500_000L, 0L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM cleanup_obligation WHERE submission_id = ?",
                                Long.class,
                                creation.submissionId()))
                .isEqualTo(1L);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM source_publication WHERE submission_id = ?",
                                Long.class,
                                creation.submissionId()))
                .isZero();
    }

    @Test
    void failedInspectionAndCancellationReleaseTheReservationWithoutCreatingASourcePublication()
            throws Exception {
        UUID rejectedListener = entitledListener("rejected-language");
        byte[] nonEnglish = epub("fr");
        PublicationSubmissionService.Creation rejected =
                submissionService.create(
                        createCommand(
                                rejectedListener,
                                nonEnglish.length,
                                sha256(nonEnglish),
                                "rejected-create"));
        PublicationSubmissionService.UploadProgress rejectedUpload =
                submissionService.upload(upload(rejected, 0, nonEnglish.length, nonEnglish));
        submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        rejectedListener,
                        rejected.submissionId(),
                        rejectedUpload.storageGeneration(),
                        nonEnglish.length,
                        sha256(nonEnglish),
                        "rejected-confirm"));
        UUID workId =
                jdbcTemplate.queryForObject(
                        "SELECT work_id FROM inspection_work WHERE submission_id = ?",
                        UUID.class,
                        rejected.submissionId());
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE work_id = ?",
                        UUID.class,
                        workId);
        inspectionWorkflowService.acceptDelivery(messageId, workId);

        InspectionOutcomeRecordingService.Inspection inspection =
                inspectionOutcomeRecordingService.inspect(
                        new InspectionOutcomeRecordingService.InspectionCommand(
                                workId,
                                "inspection-worker-rejection",
                                Instant.now().plusSeconds(60),
                                "inspect-" + workId));

        assertThat(inspection.outcome())
                .isEqualTo(InspectionOutcomeRecordingService.InspectionOutcome.REJECTED);
        assertThat(inspection.reasonCode()).isEqualTo("UNSUPPORTED_LANGUAGE");
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);
        assertThat(entitlementService.allowance(rejectedListener).reservedCharacters()).isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM source_publication WHERE submission_id = ?",
                                Long.class,
                                rejected.submissionId()))
                .isZero();

        UUID cancelledListener = entitledListener("cancelled");
        byte[] valid = validEnglishEpub();
        PublicationSubmissionService.Creation cancelled =
                submissionService.create(
                        createCommand(
                                cancelledListener,
                                valid.length,
                                sha256(valid),
                                "cancelled-create"));
        PublicationSubmissionService.Submission cancellation =
                submissionService.cancel(
                        new PublicationSubmissionService.CancelCommand(
                                cancelledListener, cancelled.submissionId(), "cancelled-command"));
        PublicationSubmissionService.Submission cancellationReplay =
                submissionService.cancel(
                        new PublicationSubmissionService.CancelCommand(
                                cancelledListener, cancelled.submissionId(), "cancelled-command"));

        assertThat(cancellation.state())
                .isEqualTo(PublicationSubmissionService.SubmissionState.CANCELLED);
        assertThat(cancellationReplay).isEqualTo(cancellation);
        assertThat(entitlementService.allowance(cancelledListener).reservedCharacters()).isZero();
    }

    @Test
    void expiredSessionsAndActiveLeasesFailClosed() throws Exception {
        UUID expiredListener = entitledListener("expired");
        byte[] valid = validEnglishEpub();
        PublicationSubmissionService.Creation expired =
                submissionService.create(
                        createCommand(
                                expiredListener, valid.length, sha256(valid), "expired-create"));
        jdbcTemplate.update(
                "UPDATE publication_submission SET upload_expires_at = ? WHERE submission_id = ?",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),
                expired.submissionId());

        assertThat(submissionService.expireDue()).isEqualTo(1);
        assertThat(submissionService.submission(expiredListener, expired.submissionId()).state())
                .isEqualTo(PublicationSubmissionService.SubmissionState.EXPIRED);
        assertThat(entitlementService.allowance(expiredListener).reservedCharacters()).isZero();

        UUID leasedListener = entitledListener("leased");
        PublicationSubmissionService.Creation leased =
                submissionService.create(
                        createCommand(
                                leasedListener, valid.length, sha256(valid), "leased-create"));
        PublicationSubmissionService.UploadProgress upload =
                submissionService.upload(upload(leased, 0, valid.length, valid));
        submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        leasedListener,
                        leased.submissionId(),
                        upload.storageGeneration(),
                        valid.length,
                        sha256(valid),
                        "leased-confirm"));
        UUID workId =
                jdbcTemplate.queryForObject(
                        "SELECT work_id FROM inspection_work WHERE submission_id = ?",
                        UUID.class,
                        leased.submissionId());
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE work_id = ?",
                        UUID.class,
                        workId);
        inspectionWorkflowService.acceptDelivery(messageId, workId);
        jdbcTemplate.update(
                "UPDATE inspection_work SET state = 'LEASED', lease_owner = ?, lease_expires_at = ?"
                        + " WHERE work_id = ?",
                "another-worker",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1),
                workId);

        assertThat(
                        inspectionOutcomeRecordingService
                                .inspect(
                                        new InspectionOutcomeRecordingService.InspectionCommand(
                                                workId,
                                                "contending-worker",
                                                Instant.now().plusSeconds(60),
                                                "inspect-" + workId))
                                .outcome())
                .isEqualTo(
                        InspectionOutcomeRecordingService.InspectionOutcome
                                .LEASED_BY_ANOTHER_WORKER);
    }

    @Test
    void admittedPdfPersistsItsDetectedFormatAndBoundedToolchain() throws Exception {
        UUID listenerId = entitledListener("pdf");
        byte[] pdf = validPdf();
        PublicationSubmissionService.Creation creation =
                submissionService.create(
                        createCommand(
                                listenerId,
                                "application/pdf",
                                pdf.length,
                                sha256(pdf),
                                "pdf-create"));
        PublicationSubmissionService.UploadProgress uploaded =
                submissionService.upload(upload(creation, 0, pdf.length, pdf));
        submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        listenerId,
                        creation.submissionId(),
                        uploaded.storageGeneration(),
                        pdf.length,
                        sha256(pdf),
                        "pdf-confirm"));
        UUID workId =
                jdbcTemplate.queryForObject(
                        "SELECT work_id FROM inspection_work WHERE submission_id = ?",
                        UUID.class,
                        creation.submissionId());
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE work_id = ?",
                        UUID.class,
                        workId);
        inspectionWorkflowService.acceptDelivery(messageId, workId);

        assertThat(inspectionWorkerService.runPending()).isEqualTo(1);
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);

        assertThat(submissionService.submission(listenerId, creation.submissionId()).state())
                .isEqualTo(PublicationSubmissionService.SubmissionState.ADMITTED);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT media_type FROM source_publication WHERE submission_id = ?",
                                String.class,
                                creation.submissionId()))
                .isEqualTo("application/pdf");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT toolchain_version FROM inspection_result WHERE work_id = ?",
                                String.class,
                                workId))
                .isEqualTo("qpdf-pdfbox-v1");
        UUID conversionId =
                submissionService.submission(listenerId, creation.submissionId()).conversionId();
        assertThat(audiobookConversionService.conversion(listenerId, conversionId).reasonCode())
                .isEqualTo("EXTRACTION_PENDING");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM workflow.narration_plan_work WHERE"
                                        + " conversion_id = ?",
                                Long.class,
                                conversionId))
                .isOne();
    }

    @Test
    void orphanedInspectionAttemptsFailClosedAfterTheConfiguredRetryLimit() throws Exception {
        UUID listenerId = entitledListener("orphan");
        byte[] epub = validEnglishEpub();
        PublicationSubmissionService.Creation creation =
                submissionService.create(
                        createCommand(listenerId, epub.length, sha256(epub), "orphan-create"));
        PublicationSubmissionService.UploadProgress uploaded =
                submissionService.upload(upload(creation, 0, epub.length, epub));
        submissionService.confirm(
                new PublicationSubmissionService.ConfirmCommand(
                        listenerId,
                        creation.submissionId(),
                        uploaded.storageGeneration(),
                        epub.length,
                        sha256(epub),
                        "orphan-confirm"));
        UUID workId =
                jdbcTemplate.queryForObject(
                        "SELECT work_id FROM inspection_work WHERE submission_id = ?",
                        UUID.class,
                        creation.submissionId());
        UUID messageId =
                jdbcTemplate.queryForObject(
                        "SELECT message_id FROM admission_outbox WHERE work_id = ?",
                        UUID.class,
                        workId);
        inspectionWorkflowService.acceptDelivery(messageId, workId);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(
                            inspectionWorkflowService
                                    .claim(
                                            workId,
                                            "crashed-worker-" + attempt,
                                            Instant.now().plusSeconds(30),
                                            "inspect-" + workId)
                                    .status())
                    .isEqualTo(InspectionWorkflowService.ClaimStatus.CLAIMED);
            jdbcTemplate.update(
                    "UPDATE inspection_work SET lease_expires_at = ? WHERE work_id = ?",
                    java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1),
                    workId);
        }

        InspectionOutcomeRecordingService.Inspection inspection =
                inspectionOutcomeRecordingService.inspect(
                        new InspectionOutcomeRecordingService.InspectionCommand(
                                workId,
                                "recovery-worker",
                                Instant.now().plusSeconds(30),
                                "inspect-" + workId));

        assertThat(inspection.outcome())
                .isEqualTo(InspectionOutcomeRecordingService.InspectionOutcome.REJECTED);
        assertThat(inspection.reasonCode()).isEqualTo("INSPECTION_RETRIES_EXHAUSTED");
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT attempt_count FROM inspection_work WHERE work_id = ?",
                                Integer.class,
                                workId))
                .isEqualTo(3);
    }

    private UUID entitledListener(String suffix) {
        UUID listenerId =
                listenerIdentityService
                        .establish(
                                new ExternalIdentity(
                                        URI.create("https://accounts.google.com"),
                                        "submission-" + suffix + "-" + UUID.randomUUID(),
                                        SignInProvider.GOOGLE,
                                        null,
                                        "Submission Listener"))
                        .listenerId();
        entitlementService.approveFreeGrant(
                listenerId, "submission-approval-" + listenerId, "submission-grant-" + listenerId);
        return listenerId;
    }

    private static PublicationSubmissionService.CreateCommand createCommand(
            UUID listenerId, long byteLength, String digest, String operation) {
        return createCommand(listenerId, "application/epub+zip", byteLength, digest, operation);
    }

    private static PublicationSubmissionService.CreateCommand createCommand(
            UUID listenerId, String mediaType, long byteLength, String digest, String operation) {
        return new PublicationSubmissionService.CreateCommand(
                listenerId, mediaType, byteLength, digest, "rights-v1", "notice-v1", operation);
    }

    private static PublicationSubmissionService.UploadCommand upload(
            PublicationSubmissionService.Creation creation,
            long offset,
            long totalBytes,
            byte[] bytes)
            throws Exception {
        return new PublicationSubmissionService.UploadCommand(
                creation.submissionId(),
                creation.uploadSession().token(),
                offset,
                totalBytes,
                sha256(bytes),
                bytes);
    }

    private static byte[] validEnglishEpub() throws Exception {
        return epub("en");
    }

    private static byte[] validPdf() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(bytes);
        }
        return bytes.toByteArray();
    }

    private static byte[] epub(String language) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            ZipEntry mimetypeEntry = new ZipEntry("mimetype");
            mimetypeEntry.setMethod(ZipEntry.STORED);
            mimetypeEntry.setSize(mimetype.length);
            mimetypeEntry.setCompressedSize(mimetype.length);
            mimetypeEntry.setCrc(crc.getValue());
            zip.putNextEntry(mimetypeEntry);
            zip.write(mimetype);
            zip.closeEntry();
            entry(
                    zip,
                    "META-INF/container.xml",
                    """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            entry(
                    zip,
                    "OEBPS/content.opf",
                    """
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="book-id">urn:uuid:synthetic</dc:identifier>
                        <dc:title>Synthetic fixture</dc:title><dc:language>%s</dc:language>
                      </metadata>
                      <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """
                            .formatted(language));
            entry(
                    zip,
                    "OEBPS/chapter.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>Chapter</title></head>
                    <body><h1>Chapter one</h1><p>A small public-domain-style test passage.</p></body></html>
                    """);
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
