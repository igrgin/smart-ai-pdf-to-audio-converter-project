package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.InspectionWorkPublisher;
import dev.audiobook.platform.admission.AdmissionOutboxRelayService;
import dev.audiobook.platform.admission.InspectionOutcomeRecordingService;
import dev.audiobook.platform.admission.MalwareScanner;
import dev.audiobook.platform.admission.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.InspectionWorkflowService;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class NarrationPlanITest {

    private static final String PRIVATE_PROSE = "A private sentence that belongs only in a Working Asset.";

    private final PublicationSubmissionService submissionService;
    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;
    private final InspectionWorkflowService inspectionWorkflowService;
    private final InspectionOutcomeRecordingService inspectionOutcomeRecordingService;
    private final AudiobookConversionService conversionService;
    private final NarrationPlanService narrationPlanService;
    private final NarrationPlanAssetStore assetStore;
    private final NarrationPlanJobService narrationPlanJobService;
    private final AdmissionOutboxRelayService outboxRelayService;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private InspectionWorkPublisher inspectionWorkPublisher;

    @MockitoBean
    private MalwareScanner malwareScanner;

    @MockitoSpyBean
    private NarrationPlanAssetStore spiedAssetStore;

    @Autowired
    NarrationPlanITest(
            PublicationSubmissionService submissionService,
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            InspectionWorkflowService inspectionWorkflowService,
            InspectionOutcomeRecordingService inspectionOutcomeRecordingService,
            AudiobookConversionService conversionService,
            NarrationPlanService narrationPlanService,
            NarrationPlanAssetStore assetStore,
            NarrationPlanJobService narrationPlanJobService,
            AdmissionOutboxRelayService outboxRelayService,
            JdbcTemplate jdbcTemplate) {
        this.submissionService = submissionService;
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.inspectionWorkflowService = inspectionWorkflowService;
        this.inspectionOutcomeRecordingService = inspectionOutcomeRecordingService;
        this.conversionService = conversionService;
        this.narrationPlanService = narrationPlanService;
        this.assetStore = assetStore;
        this.narrationPlanJobService = narrationPlanJobService;
        this.outboxRelayService = outboxRelayService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void allowCleanPublicationsThroughTheInspectionBoundary() {
        org.mockito.Mockito.when(malwareScanner.scan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(MalwareScanner.Result.CLEAN);
    }

    @Test
    void admittedEpubProducesAnOwnerScopedAwaitingReviewPlanWhosePrivateContentLivesOnlyInAWorkingAsset()
            throws Exception {
        UUID listenerId = entitledListener();
        byte[] epub = epub();
        String digest = sha256(epub);
        PublicationSubmissionService.Creation creation = submissionService.create(
                new PublicationSubmissionService.CreateCommand(
                        listenerId,
                        "application/epub+zip",
                        epub.length,
                        digest,
                        "rights-v1",
                        "notice-v1",
                        "narration-plan-create"));
        PublicationSubmissionService.UploadProgress upload = submissionService.upload(
                new PublicationSubmissionService.UploadCommand(
                        creation.submissionId(),
                        creation.uploadSession().token(),
                        0,
                        epub.length,
                        digest,
                        epub));
        submissionService.confirm(new PublicationSubmissionService.ConfirmCommand(
                listenerId,
                creation.submissionId(),
                upload.storageGeneration(),
                epub.length,
                digest,
                "narration-plan-confirm"));
        UUID workId = jdbcTemplate.queryForObject(
                "SELECT work_id FROM workflow.inspection_work WHERE submission_id = ?",
                UUID.class,
                creation.submissionId());
        UUID messageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM workflow.admission_outbox WHERE work_id = ?",
                UUID.class,
                workId);
        assertThat(outboxRelayService.relayPending()).isEqualTo(1);
        inspectionWorkflowService.acceptDelivery(messageId, workId);

        InspectionOutcomeRecordingService.Inspection inspection = inspectionOutcomeRecordingService.inspect(
                new InspectionOutcomeRecordingService.InspectionCommand(
                        workId,
                        "narration-inspection-worker",
                        Instant.now().plusSeconds(60),
                        "inspect-" + workId));
        assertThat(inspection.outcome()).isEqualTo(InspectionOutcomeRecordingService.InspectionOutcome.ADMITTED);
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);
        UUID conversionId = submissionService.submission(listenerId, creation.submissionId()).conversionId();

        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PREPARING);
        assertThat(outboxRelayService.relayPending()).isZero();
        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PREPARING);
        assertThat(conversionService.applyNarrationPlanResults(List.of())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT count(*) FROM workflow.narration_plan_work w
                        JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                        WHERE w.conversion_id = ? AND w.state = 'SUCCEEDED' AND o.published_at IS NULL
                        """,
                        Integer.class,
                        conversionId))
                .isEqualTo(1);

        AudiobookConversionService.AudiobookConversion conversion =
                conversionService.conversion(listenerId, conversionId);
        assertThat(conversion.state()).isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
        assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_AVAILABLE");
        assertThat(conversion.allowedActions()).containsExactly(
                AudiobookConversionService.AllowedAction.REVIEW_NARRATION_PLAN,
                AudiobookConversionService.AllowedAction.ACCEPT_RECOMMENDATIONS);

        NarrationPlanService.PlanView view = narrationPlanService.plan(listenerId, conversionId);
        assertThat(view.normalProseEditable()).isFalse();
        assertThat(view.chapters()).extracting(NarrationPlanService.ChapterView::title)
                .containsExactly("Evidence");
        assertThat(view.chapters().getFirst().reviewItems()).singleElement().satisfies(item -> {
            assertThat(item.sourceOrdinal()).isEqualTo(1);
            assertThat(item.type()).isEqualTo("TABLE");
            assertThat(item.recommendedTreatment()).isEqualTo("READ_VERBATIM");
            assertThat(item.narrationSnippet()).isEqualTo("Year 2026");
        });
        assertThat(view.toString()).doesNotContain(PRIVATE_PROSE);

        StoredPlan stored = jdbcTemplate.queryForObject(
                """
                SELECT working_asset_ref, asset_sha256, to_jsonb(plan)::text AS relational_value
                FROM narration.narration_plan plan WHERE conversion_id = ?
                """,
                (resultSet, row) -> new StoredPlan(
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256"),
                        resultSet.getString("relational_value")),
                conversionId);
        assertThat(stored).isNotNull();
        assertThat(stored.relationalValue()).doesNotContain(PRIVATE_PROSE, "Year 2026", "Evidence");
        byte[] workingAsset = assetStore.read(conversionId, stored.reference());
        assertThat(sha256(workingAsset)).isEqualTo(stored.sha256());
        assertThat(new String(workingAsset, StandardCharsets.UTF_8))
                .contains(PRIVATE_PROSE, "Year 2026", "Evidence");
    }

    @Test
    void narrationWorkerRetriesDurablePreparingWorkAfterWorkingAssetFailure() throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, "retry");

        doThrow(new java.io.IOException("working asset unavailable"))
                .doCallRealMethod()
                .when(spiedAssetStore)
                .write(eq(conversionId), any(byte[].class));

        assertThatThrownBy(narrationPlanJobService::processPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan Working Asset storage is unavailable");
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PREPARING);

        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(conversionService.applyNarrationPlanResults(List.of())).isEqualTo(1);
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
    }

    @Test
    void coreReconcilesAPlanPersistedBeforeTheWorkerCouldCompleteItsLease() throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, "persisted-before-completion");
        WorkCoordinates coordinates = narrationWork(conversionId);

        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'CLAIMED', completed_at = NULL, lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                WHERE work_id = ?
                """,
                coordinates.messageId(),
                coordinates.workId());

        assertThat(conversionService.applyNarrationPlanResults(List.of(conversionId))).isEqualTo(1);
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT state || ':' || (lease_owner IS NULL) || ':' || (lease_expires_at IS NULL)
                        FROM workflow.narration_plan_work WHERE work_id = ?
                        """,
                        String.class,
                        coordinates.workId()))
                .isEqualTo("SUCCEEDED:true:true");
    }

    @Test
    void duplicateDeliveryRespectsActiveAndExpiredLeasesWithoutDuplicatingTheInbox() throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, "leases");
        WorkCoordinates coordinates = narrationWork(conversionId);
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET state = 'CLAIMED', attempt_count = 1, lease_owner = ?,
                    lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
                WHERE work_id = ?
                """,
                coordinates.messageId(),
                coordinates.workId());

        assertThat(narrationPlanJobService.processDelivery(coordinates.messageId(), coordinates.workId()))
                .isFalse();
        jdbcTemplate.update(
                """
                UPDATE workflow.narration_plan_work
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE work_id = ?
                """,
                coordinates.workId());

        assertThat(narrationPlanJobService.processDelivery(coordinates.messageId(), coordinates.workId()))
                .isTrue();
        assertThat(narrationPlanJobService.processDelivery(coordinates.messageId(), coordinates.workId()))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM workflow.narration_plan_inbox WHERE message_id = ?",
                        Integer.class,
                        coordinates.messageId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM workflow.narration_plan_work WHERE work_id = ?",
                        Integer.class,
                        coordinates.workId()))
                .isEqualTo(2);
    }

    @Test
    void repeatedDependencyFailureExhaustsDurableWorkAtTheAttemptBoundary() throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, "exhaustion");
        WorkCoordinates coordinates = narrationWork(conversionId);
        doThrow(new java.io.IOException("working asset unavailable"))
                .when(spiedAssetStore)
                .write(eq(conversionId), any(byte[].class));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThatThrownBy(narrationPlanJobService::processPending)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Narration Plan Working Asset storage is unavailable");
        }

        assertThat(narrationPlanJobService.processPending()).isZero();
        assertThat(conversionService.applyNarrationPlanResults(List.of())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT state || ':' || attempt_count
                        FROM workflow.narration_plan_work WHERE work_id = ?
                        """,
                        String.class,
                        coordinates.workId()))
                .isEqualTo("EXHAUSTED:4");
        assertThat(conversionService.conversion(listenerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.state()).isEqualTo(AudiobookConversionService.ConversionState.PREPARING);
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_PLAN_REQUIRES_INTERVENTION");
            assertThat(conversion.allowedActions()).isEmpty();
        });
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM workflow.narration_plan_inbox WHERE message_id = ?",
                        Integer.class,
                        coordinates.messageId()))
                .isEqualTo(1);
    }

    @Test
    void narrationWorkerDatabaseRoleHasOnlyItsRequiredTablesAndNoCloudAdminMembership() throws Exception {
        String databaseUrl;
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
            databaseUrl = connection.getMetaData().getURL().split("\\?", 2)[0];
        }

        try (var connection = DriverManager.getConnection(
                databaseUrl, "folio_narration_worker", "narration-integration-test-only");
                var statement = connection.createStatement()) {
            try (var grants = statement.executeQuery(
                    """
                    SELECT
                      has_table_privilege(current_user, 'workflow.narration_plan_work', 'SELECT'),
                      has_table_privilege(current_user, 'workflow.narration_plan_work', 'UPDATE'),
                      has_table_privilege(current_user, 'workflow.narration_plan_work', 'DELETE'),
                      has_schema_privilege(current_user, 'admission', 'USAGE'),
                      pg_has_role(current_user, 'cloudsqlsuperuser', 'member'),
                      (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)
                    """)) {
                assertThat(grants.next()).isTrue();
                assertThat(java.util.List.of(
                                grants.getBoolean(1),
                                grants.getBoolean(2),
                                grants.getBoolean(3),
                                grants.getBoolean(4),
                                grants.getBoolean(5),
                                grants.getBoolean(6)))
                        .containsExactly(true, true, false, false, false, false);
            }
            try (var rowLevelSecurity = statement.executeQuery(
                    """
                    SELECT bool_and(c.relrowsecurity)
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE (n.nspname, c.relname) IN (
                      ('workflow', 'narration_plan_work'),
                      ('workflow', 'audiobook_conversion'),
                      ('narration', 'narration_plan')
                    )
                    """)) {
                assertThat(rowLevelSecurity.next()).isTrue();
                assertThat(rowLevelSecurity.getBoolean(1)).isTrue();
            }
        }
    }

    private UUID admit(UUID listenerId, String operationSuffix) throws Exception {
        byte[] source = epub();
        String digest = sha256(source);
        PublicationSubmissionService.Creation creation = submissionService.create(
                new PublicationSubmissionService.CreateCommand(
                        listenerId,
                        "application/epub+zip",
                        source.length,
                        digest,
                        "rights-v1",
                        "notice-v1",
                        "narration-plan-create-" + operationSuffix));
        PublicationSubmissionService.UploadProgress upload = submissionService.upload(
                new PublicationSubmissionService.UploadCommand(
                        creation.submissionId(),
                        creation.uploadSession().token(),
                        0,
                        source.length,
                        digest,
                        source));
        submissionService.confirm(new PublicationSubmissionService.ConfirmCommand(
                listenerId,
                creation.submissionId(),
                upload.storageGeneration(),
                source.length,
                digest,
                "narration-plan-confirm-" + operationSuffix));
        UUID workId = jdbcTemplate.queryForObject(
                "SELECT work_id FROM workflow.inspection_work WHERE submission_id = ?",
                UUID.class,
                creation.submissionId());
        UUID messageId = jdbcTemplate.queryForObject(
                "SELECT message_id FROM workflow.admission_outbox WHERE work_id = ?",
                UUID.class,
                workId);
        assertThat(outboxRelayService.relayPending()).isEqualTo(1);
        inspectionWorkflowService.acceptDelivery(messageId, workId);
        InspectionOutcomeRecordingService.Inspection inspection = inspectionOutcomeRecordingService.inspect(
                new InspectionOutcomeRecordingService.InspectionCommand(
                workId,
                "narration-inspection-worker",
                Instant.now().plusSeconds(60),
                "inspect-" + workId));
        assertThat(inspection.outcome()).isEqualTo(InspectionOutcomeRecordingService.InspectionOutcome.ADMITTED);
        assertThat(submissionService.applyInspectionResults()).isEqualTo(1);
        return submissionService.submission(listenerId, creation.submissionId()).conversionId();
    }

    private WorkCoordinates narrationWork(UUID conversionId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT o.message_id, w.work_id
                FROM workflow.narration_plan_work w
                JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                WHERE w.conversion_id = ?
                """,
                (resultSet, row) -> new WorkCoordinates(
                        resultSet.getObject("message_id", UUID.class),
                        resultSet.getObject("work_id", UUID.class)),
                conversionId);
    }

    private UUID entitledListener() {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "narration-plan-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Narration Listener"))
                .listenerId();
        entitlementService.approveFreeGrant(
                listenerId,
                "narration-plan-approval-" + listenerId,
                "narration-plan-grant-" + listenerId);
        return listenerId;
    }

    private static byte[] epub() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            byte[] mediaType = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(mediaType);
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            mimetype.setSize(mediaType.length);
            mimetype.setCompressedSize(mediaType.length);
            mimetype.setCrc(crc.getValue());
            zip.putNextEntry(mimetype);
            zip.write(mediaType);
            zip.closeEntry();
            entry(zip, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                      <rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>
                    """);
            entry(zip, "OPS/package.opf", """
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:language>en</dc:language></metadata>
                      <manifest>
                        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                        <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="chapter"/></spine>
                    </package>
                    """);
            entry(zip, "OPS/nav.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
                      <nav epub:type="toc"><ol><li><a href="chapter.xhtml#evidence">Evidence</a></li></ol></nav>
                    </body></html>
                    """);
            entry(zip, "OPS/chapter.xhtml", """
                    <html xmlns="http://www.w3.org/1999/xhtml"><body>
                      <h1 id="evidence">Evidence heading</h1>
                      <p>%s</p>
                      <table id="facts"><tr><td>Year</td><td>2026</td></tr></table>
                    </body></html>
                    """.formatted(PRIVATE_PROSE));
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record StoredPlan(String reference, String sha256, String relationalValue) {
    }

    private record WorkCoordinates(UUID messageId, UUID workId) {
    }
}
