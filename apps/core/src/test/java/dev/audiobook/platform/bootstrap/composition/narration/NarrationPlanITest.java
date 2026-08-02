package dev.audiobook.platform.bootstrap.composition.narration;

import dev.audiobook.platform.narration.*;

import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.narration.internal.document.PdfDocumentUnderstandingBoundary;
import dev.audiobook.platform.narration.SourceTooDamagedException;
import dev.audiobook.platform.narration.internal.review.NarrationReviewRejectedException;
import dev.audiobook.platform.narration.internal.review.NarrationReviewRejectionReason;
import dev.audiobook.platform.worker.internal.NarrationPlanJobService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.internal.delivery.InspectionWorkPublisher;
import dev.audiobook.platform.admission.internal.delivery.AdmissionOutboxRelayService;
import dev.audiobook.platform.admission.internal.inspection.InspectionOutcomeRecordingService;
import dev.audiobook.platform.admission.internal.inspection.MalwareScanner;
import dev.audiobook.platform.admission.internal.submission.PublicationSubmissionService;
import dev.audiobook.platform.admission.internal.inspection.QpdfValidationService;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.internal.oidc.ExternalIdentity;
import dev.audiobook.platform.identity.internal.session.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.internal.AudiobookConversionUnavailableException;
import dev.audiobook.platform.admission.internal.inspection.InspectionWorkflowService;
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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@AutoConfigureMockMvc
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
    private final NarrationReviewService narrationReviewService;
    private final NarrationReviewAssetStore reviewAssetStore;
    private final NarrationPlanAssetStore assetStore;
    private final NarrationPlanJobService narrationPlanJobService;
    private final AdmissionOutboxRelayService outboxRelayService;
    private final JdbcTemplate jdbcTemplate;
    private final MockMvc mockMvc;

    @MockitoBean
    private InspectionWorkPublisher inspectionWorkPublisher;

    @MockitoBean
    private MalwareScanner malwareScanner;

    @MockitoBean
    private QpdfValidationService qpdfValidationService;

    @MockitoBean
    private PdfDocumentUnderstandingBoundary pdfDocumentUnderstandingBoundary;

    @MockitoSpyBean
    private NarrationPlanAssetStore spiedAssetStore;

    @MockitoSpyBean
    private NarrationReviewAssetStore spiedReviewAssetStore;

    @Autowired
    NarrationPlanITest(
            PublicationSubmissionService submissionService,
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            InspectionWorkflowService inspectionWorkflowService,
            InspectionOutcomeRecordingService inspectionOutcomeRecordingService,
            AudiobookConversionService conversionService,
            NarrationPlanService narrationPlanService,
            NarrationReviewService narrationReviewService,
            NarrationReviewAssetStore reviewAssetStore,
            NarrationPlanAssetStore assetStore,
            NarrationPlanJobService narrationPlanJobService,
            AdmissionOutboxRelayService outboxRelayService,
            JdbcTemplate jdbcTemplate,
            MockMvc mockMvc) {
        this.submissionService = submissionService;
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.inspectionWorkflowService = inspectionWorkflowService;
        this.inspectionOutcomeRecordingService = inspectionOutcomeRecordingService;
        this.conversionService = conversionService;
        this.narrationPlanService = narrationPlanService;
        this.narrationReviewService = narrationReviewService;
        this.reviewAssetStore = reviewAssetStore;
        this.assetStore = assetStore;
        this.narrationPlanJobService = narrationPlanJobService;
        this.outboxRelayService = outboxRelayService;
        this.jdbcTemplate = jdbcTemplate;
        this.mockMvc = mockMvc;
    }

    @BeforeEach
    void allowCleanPublicationsThroughTheInspectionBoundary() {
        org.mockito.Mockito.when(malwareScanner.scan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(MalwareScanner.Result.CLEAN);
        given(qpdfValidationService.validate(any())).willReturn(QpdfValidationService.Result.VALID);
    }

    @Test
    void admittedPdfProducesTheSameProvenanceBackedNarrationPlanWorkflow() throws Exception {
        given(pdfDocumentUnderstandingBoundary.inspect(any())).willReturn(
                new PdfDocumentUnderstandingBoundary.DocumentProfile(
                        2,
                        List.of(new PdfDocumentUnderstandingBoundary.OutlineEntry(
                                "Scanned evidence", 1, "outline-1"))));
        given(pdfDocumentUnderstandingBoundary.understandBatch(
                        any(), any(PdfDocumentUnderstandingBoundary.PageRange.class)))
                .willAnswer(invocation -> {
                    PdfDocumentUnderstandingBoundary.PageRange range = invocation.getArgument(1);
                    return range.pages()
                            .mapToObj(page -> new PdfDocumentUnderstandingBoundary.PageEvidence(
                                    page,
                                    page == 1 ? PRIVATE_PROSE : "A locally recovered scanned page.",
                                    page == 1
                                            ? PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT
                                            : PdfDocumentUnderstandingBoundary.TextSource.TESSERACT_OCR,
                                    List.of()))
                            .toList();
                });
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, pdf(), "application/pdf", "pdf-success");

        assertThat(conversionService.conversion(listenerId, conversionId).reasonCode())
                .isEqualTo("EXTRACTION_PENDING");
        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);

        assertThat(conversionService.conversion(listenerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.state())
                    .isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_AVAILABLE");
        });
        assertThat(narrationPlanService.plan(listenerId, conversionId).chapters())
                .singleElement()
                .satisfies(chapter -> {
                    assertThat(chapter.title()).isEqualTo("Scanned evidence");
                    assertThat(chapter.provenance().source()).isEqualTo("PDF_OUTLINE");
                });
        String assetReference = jdbcTemplate.queryForObject(
                "SELECT working_asset_ref FROM narration.narration_plan WHERE conversion_id = ?",
                String.class,
                conversionId);
        assertThat(new String(assetStore.read(conversionId, assetReference), StandardCharsets.UTF_8))
                .contains(PRIVATE_PROSE, "A locally recovered scanned page.", "TESSERACT_OCR");
    }

    @Test
    void pdfBeyondTheApprovedConsecutiveGapLimitPausesWithAResumeCheckpoint() throws Exception {
        given(pdfDocumentUnderstandingBoundary.inspect(any())).willReturn(
                new PdfDocumentUnderstandingBoundary.DocumentProfile(3, List.of()));
        given(pdfDocumentUnderstandingBoundary.understandBatch(
                        any(), any(PdfDocumentUnderstandingBoundary.PageRange.class)))
                .willAnswer(invocation -> {
                    PdfDocumentUnderstandingBoundary.PageRange range = invocation.getArgument(1);
                    return range.pages()
                            .mapToObj(page -> new PdfDocumentUnderstandingBoundary.PageEvidence(
                                    page,
                                    page == 1 ? "Readable beginning." : "",
                                    page == 1
                                            ? PdfDocumentUnderstandingBoundary.TextSource.PDFBOX_TEXT
                                            : PdfDocumentUnderstandingBoundary.TextSource.UNREADABLE,
                                    List.of()))
                            .toList();
                });
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, pdf(3), "application/pdf", "pdf-damaged");

        assertThat(narrationPlanJobService.processPending()).isZero();
        assertThat(conversionService.applyNarrationPlanResults()).isZero();

        AudiobookConversionService.AudiobookConversion paused =
                conversionService.conversion(listenerId, conversionId);
        assertThat(paused).satisfies(conversion -> {
            assertThat(conversion.state()).isEqualTo(AudiobookConversionService.ConversionState.PAUSED);
            assertThat(conversion.reasonCode()).isEqualTo("SOURCE_TOO_DAMAGED");
            assertThat(conversion.allowedActions())
                    .containsExactly(AudiobookConversionService.AllowedAction.RETRY_NARRATION_PLAN);
            assertThat(conversion.recovery())
                    .isEqualTo(new AudiobookConversionService.RecoveryDetails(
                            3, SourceTooDamagedException.LISTENER_GUIDANCE));
        });
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT state || ':' || pause_reason_code || ':' || resume_from_page || ':' || listener_guidance
                        FROM workflow.narration_plan_work WHERE conversion_id = ?
                        """,
                        String.class,
                        conversionId))
                .isEqualTo("PAUSED:SOURCE_TOO_DAMAGED:3:" + SourceTooDamagedException.LISTENER_GUIDANCE);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM narration.narration_plan WHERE conversion_id = ?",
                        Integer.class,
                        conversionId))
                .isZero();

        mockMvc.perform(get("/api/v1/audiobook-conversions/" + conversionId)
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"))
                .andExpect(jsonPath("$.reasonCode").value("SOURCE_TOO_DAMAGED"))
                .andExpect(jsonPath("$.recovery.resumeFromPage").value(3))
                .andExpect(jsonPath("$.recovery.listenerGuidance")
                        .value(SourceTooDamagedException.LISTENER_GUIDANCE));

        UUID otherListener = entitledListener();
        mockMvc.perform(get("/api/v1/audiobook-conversions/" + conversionId)
                        .with(authentication(listenerAuthentication(otherListener))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSION_UNAVAILABLE"));
        assertThatThrownBy(() -> conversionService.resumeNarrationPlan(
                        otherListener, conversionId, paused.version(), "cross-listener-retry"))
                .isInstanceOf(AudiobookConversionUnavailableException.class);
        assertThatThrownBy(() -> conversionService.resumeNarrationPlan(
                        listenerId, conversionId, paused.version() + 1, "stale-retry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");

        mockMvc.perform(post("/api/v1/audiobook-conversions/" + conversionId + "/narration-plan-recovery")
                        .header("Idempotency-Key", "retry-damaged-pdf")
                        .header("If-Match", "\"" + paused.version() + "\"")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(listenerAuthentication(listenerId)))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PREPARING"))
                .andExpect(jsonPath("$.reasonCode").value("NARRATION_PLAN_PENDING"));
        AudiobookConversionService.AudiobookConversion resumed =
                conversionService.conversion(listenerId, conversionId);
        assertThat(resumed.state()).isEqualTo(AudiobookConversionService.ConversionState.PREPARING);
        assertThat(resumed.reasonCode()).isEqualTo("NARRATION_PLAN_PENDING");
        assertThat(resumed.recovery()).isNull();
        assertThat(conversionService.resumeNarrationPlan(
                        listenerId, conversionId, paused.version(), "retry-damaged-pdf"))
                .isEqualTo(resumed);
        assertThatThrownBy(() -> conversionService.resumeNarrationPlan(
                        listenerId, conversionId, paused.version() + 1, "retry-damaged-pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already used");
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT w.state || ':' || (o.published_at IS NULL)
                        FROM workflow.narration_plan_work w
                        JOIN workflow.narration_plan_outbox o ON o.work_id = w.work_id
                        WHERE w.conversion_id = ?
                        """,
                        String.class,
                        conversionId))
                .isEqualTo("READY:true");
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication(UUID listenerId) {
        var principal = new dev.audiobook.platform.identity.ListenerPrincipal(
                listenerId,
                "Narration Listener",
                null,
                java.util.Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
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
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);
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
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.AWAITING_REVIEW);
    }

    @Test
    void skippingOptionalReviewFreezesRecommendationsAndReplaysWithoutRelationalPrivateContent()
            throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = admit(listenerId, "skip-review");
        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);

        NarrationReviewService.ReviewCommand command = new NarrationReviewService.ReviewCommand(
                listenerId,
                conversionId,
                NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                List.of(),
                1,
                "skip-review-operation");
        NarrationReviewService.ReviewResult first = narrationReviewService.submit(command);
        NarrationReviewService.ReviewResult replay = narrationReviewService.submit(command);

        assertThat(first.action()).isEqualTo(NarrationReviewService.ReviewAction.SKIP_OPTIONAL);
        assertThat(first.conversionVersion()).isEqualTo(2);
        assertThat(first.replayed()).isFalse();
        assertThat(replay).isEqualTo(new NarrationReviewService.ReviewResult(
                first.decisionId(),
                first.action(),
                first.conversionVersion(),
                true));
        assertThat(conversionService.conversion(listenerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_RECOMMENDATIONS_ACCEPTED");
            assertThat(conversion.version()).isEqualTo(2);
            assertThat(conversion.allowedActions()).isEmpty();
        });
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT to_jsonb(decision)::text || to_jsonb(operation)::text
                        FROM narration.narration_review_decision decision
                        JOIN narration.narration_review_operation operation USING (decision_id)
                        WHERE decision.conversion_id = ?
                        """,
                        String.class,
                        conversionId))
                .doesNotContain(PRIVATE_PROSE, "Evidence", "Year 2026");
    }

    @Test
    void explicitApprovalFreezesAReorderedSplitStructureAndOnlyMutableReviewItemFields()
            throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = readyReview(listenerId, "approve-review");
        List<NarrationReviewService.SectionDecision> sections = List.of(
                new NarrationReviewService.SectionDecision(
                        "continued-evidence",
                        "Findings continued",
                        true,
                        List.of(0),
                        List.of()),
                new NarrationReviewService.SectionDecision(
                        "primary-evidence",
                        "Findings",
                        false,
                        List.of(0),
                        List.of(new NarrationReviewService.ReviewItemDecision(
                                0,
                                0,
                                NarrationReviewService.Treatment.DESCRIBE,
                                "Describe the 2026 evidence table."))));
        NarrationReviewService.ReviewCommand command = new NarrationReviewService.ReviewCommand(
                listenerId,
                conversionId,
                NarrationReviewService.ReviewAction.APPROVE,
                sections,
                1,
                "approve-review-operation");

        NarrationReviewService.ReviewResult result = narrationReviewService.submit(command);

        assertThat(result.action()).isEqualTo(NarrationReviewService.ReviewAction.APPROVE);
        assertThat(result.conversionVersion()).isEqualTo(2);
        assertThat(conversionService.conversion(listenerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_APPROVED");
            assertThat(conversion.allowedActions()).isEmpty();
        });
        StoredReview stored = jdbcTemplate.queryForObject(
                """
                SELECT decision_id, working_asset_ref, asset_sha256
                FROM narration.narration_review_decision WHERE conversion_id = ?
                """,
                (resultSet, row) -> new StoredReview(
                        resultSet.getObject("decision_id", UUID.class),
                        resultSet.getString("working_asset_ref"),
                        resultSet.getString("asset_sha256")),
                conversionId);
        assertThat(stored).isNotNull();
        byte[] frozen = reviewAssetStore.read(conversionId, stored.decisionId(), stored.reference());
        assertThat(sha256(frozen)).isEqualTo(stored.sha256());
        assertThat(new String(frozen, StandardCharsets.UTF_8))
                .contains("Findings continued", "Findings", "DESCRIBE", "Describe the 2026 evidence table.")
                .doesNotContain(PRIVATE_PROSE, "provenance", "confidence", "sourceOrdinal", "type");

        NarrationReviewService.ReviewCommand reusedKey = new NarrationReviewService.ReviewCommand(
                listenerId,
                conversionId,
                NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                List.of(),
                1,
                "approve-review-operation");
        assertThatThrownBy(() -> narrationReviewService.submit(reusedKey))
                .isInstanceOfSatisfying(NarrationReviewRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(NarrationReviewRejectionReason.IDEMPOTENCY_KEY_REUSED));
    }

    @Test
    void staleAndCrossListenerReviewWritesFailWithoutDisclosingOrChangingThePlan() throws Exception {
        UUID ownerId = entitledListener();
        UUID conversionId = readyReview(ownerId, "review-ownership");
        UUID otherListenerId = entitledListener();

        assertThatThrownBy(() -> narrationReviewService.submit(new NarrationReviewService.ReviewCommand(
                        ownerId,
                        conversionId,
                        NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                        List.of(),
                        0,
                        "stale-review-operation")))
                .isInstanceOfSatisfying(NarrationReviewRejectedException.class, exception -> {
                    assertThat(exception.reason())
                            .isEqualTo(NarrationReviewRejectionReason.CONVERSION_VERSION_MISMATCH);
                    assertThat(exception.currentVersion()).isEqualTo(1);
                });
        assertThatThrownBy(() -> narrationReviewService.submit(new NarrationReviewService.ReviewCommand(
                        otherListenerId,
                        conversionId,
                        NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                        List.of(),
                        1,
                        "cross-listener-review-operation")))
                .isInstanceOfSatisfying(NarrationReviewRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(NarrationReviewRejectionReason.CONVERSION_UNAVAILABLE));
        assertThat(conversionService.conversion(ownerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_AVAILABLE");
            assertThat(conversion.version()).isEqualTo(1);
        });
    }

    @Test
    void reviewWorkingAssetFailureLeavesTheReviewAvailableForARecoverableRetry() throws Exception {
        UUID listenerId = entitledListener();
        UUID conversionId = readyReview(listenerId, "review-storage-failure");
        doThrow(new java.io.IOException("review asset unavailable"))
                .when(spiedReviewAssetStore)
                .write(eq(conversionId), any(UUID.class), any(byte[].class));

        assertThatThrownBy(() -> narrationReviewService.submit(new NarrationReviewService.ReviewCommand(
                        listenerId,
                        conversionId,
                        NarrationReviewService.ReviewAction.SKIP_OPTIONAL,
                        List.of(),
                        1,
                        "review-storage-failure-operation")))
                .isInstanceOfSatisfying(NarrationReviewRejectedException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(NarrationReviewRejectionReason.WORKING_ASSET_UNAVAILABLE));
        assertThat(conversionService.conversion(listenerId, conversionId)).satisfies(conversion -> {
            assertThat(conversion.reasonCode()).isEqualTo("NARRATION_REVIEW_AVAILABLE");
            assertThat(conversion.version()).isEqualTo(1);
        });
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM narration.narration_review_decision WHERE conversion_id = ?",
                        Integer.class,
                        conversionId))
                .isZero();
    }

    @Test
    void coreDoesNotAdvanceAPlanWhoseAcceptedWorkStateWasRolledBack() throws Exception {
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

        assertThat(conversionService.applyNarrationPlanResults()).isZero();
        assertThat(conversionService.conversion(listenerId, conversionId).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PREPARING);
        assertThat(jdbcTemplate.queryForObject(
                        """
                        SELECT state || ':' || (lease_owner IS NULL) || ':' || (lease_expires_at IS NULL)
                        FROM workflow.narration_plan_work WHERE work_id = ?
                        """,
                        String.class,
                        coordinates.workId()))
                .isEqualTo("CLAIMED:false:false");
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
        jdbcTemplate.update(
                """
                UPDATE workflow.conversion_stage_run
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE conversion_id = ? AND stage = 'NARRATION_ANALYSIS'
                """,
                conversionId);

        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(narrationPlanJobService.processDelivery(coordinates.messageId(), coordinates.workId()))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM workflow.narration_plan_inbox WHERE message_id = ?",
                        Integer.class,
                        coordinates.messageId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM workflow.narration_plan_delivery WHERE work_id = ?",
                        Integer.class,
                        coordinates.workId()))
                .isEqualTo(2);
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
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);
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
        return admit(listenerId, epub(), "application/epub+zip", operationSuffix);
    }

    private UUID admit(UUID listenerId, byte[] source, String mediaType, String operationSuffix) throws Exception {
        String digest = sha256(source);
        PublicationSubmissionService.Creation creation = submissionService.create(
                new PublicationSubmissionService.CreateCommand(
                        listenerId,
                        mediaType,
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

    private UUID readyReview(UUID listenerId, String operationSuffix) throws Exception {
        UUID conversionId = admit(listenerId, operationSuffix);
        assertThat(narrationPlanJobService.processPending()).isEqualTo(1);
        assertThat(conversionService.applyNarrationPlanResults()).isEqualTo(1);
        return conversionId;
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

    private static byte[] pdf() throws Exception {
        return pdf(2);
    }

    private static byte[] pdf(int pageCount) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage());
            }
            document.save(bytes);
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

    private record StoredReview(UUID decisionId, String reference, String sha256) {
    }

    private record WorkCoordinates(UUID messageId, UUID workId) {
    }
}
