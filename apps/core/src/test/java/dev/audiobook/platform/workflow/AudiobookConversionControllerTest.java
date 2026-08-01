package dev.audiobook.platform.workflow;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.AdmissionOutboxRelayService;
import dev.audiobook.platform.admission.InspectionWorkPublisher;
import dev.audiobook.platform.admission.PubSubPushAuthenticator;
import dev.audiobook.platform.admission.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.narration.NarrationPlanService;
import dev.audiobook.platform.narration.NarrationReviewService;
import dev.audiobook.platform.narration.NarrationReviewRejectedException;
import dev.audiobook.platform.narration.NarrationReviewRejectionReason;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class AudiobookConversionControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000025");
    private static final UUID CONVERSION_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000125");

    @MockitoBean
    private AudiobookConversionService conversionService;

    @MockitoBean
    private NarrationPlanService narrationPlanService;

    @MockitoBean
    private NarrationReviewService narrationReviewService;

    @MockitoBean
    private PublicationSubmissionService submissionService;

    @MockitoBean
    private AdmissionOutboxRelayService outboxRelayService;

    @MockitoBean
    private InspectionWorkPublisher inspectionWorkPublisher;

    @MockitoBean
    private PubSubPushAuthenticator pubSubPushAuthenticator;

    @MockitoBean
    private InspectionWorkflowService inspectionWorkflowService;

    @MockitoBean
    private ConversionEntitlementService entitlementService;

    @MockitoBean
    private ListenerIdentityService listenerIdentityService;

    @MockitoBean
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listenerConditionallyPollsStableAwaitingReviewProgressWithoutNormalProse() throws Exception {
        when(conversionService.conversion(LISTENER_ID, CONVERSION_ID)).thenReturn(
                new AudiobookConversionService.AudiobookConversion(
                        CONVERSION_ID,
                        AudiobookConversionService.ConversionState.AWAITING_REVIEW,
                        "NARRATION_REVIEW_AVAILABLE",
                        List.of(
                                AudiobookConversionService.AllowedAction.REVIEW_NARRATION_PLAN,
                                AudiobookConversionService.AllowedAction.ACCEPT_RECOMMENDATIONS),
                        1));
        when(narrationPlanService.plan(LISTENER_ID, CONVERSION_ID)).thenReturn(
                new NarrationPlanService.PlanView(
                        List.of(new NarrationPlanService.ChapterView(
                                0,
                                "Evidence",
                                new NarrationPlanService.ProvenanceView(
                                        "EPUB_NAVIGATION", 0, "OPS/chapter.xhtml", "start", true, 1.0),
                                List.of(),
                                List.of(new NarrationPlanService.ReviewItemView(
                                        0,
                                        1,
                                        "TABLE",
                                        new NarrationPlanService.ProvenanceView(
                                                "EPUB_XHTML", 0, "OPS/chapter.xhtml", "facts", true, 1.0),
                                        0.99,
                                        0.98,
                                        0.93,
                                        "READ_VERBATIM",
                                        "Year 2026",
                                        "TABLE_DETECTED")))),
                        false));

        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID)
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.reasonCode").value("NARRATION_REVIEW_AVAILABLE"))
                .andExpect(jsonPath("$.allowedActions[0]").value("REVIEW_NARRATION_PLAN"))
                .andExpect(jsonPath("$.narrationPlan.normalProseEditable").value(false))
                .andExpect(jsonPath("$.narrationPlan.chapters[0].reviewItems[0].sourceOrdinal").value(1))
                .andExpect(jsonPath("$.narrationPlan.chapters[0].reviewItems[0].extractionConfidence").value(0.99))
                .andExpect(jsonPath("$.narrationPlan.chapters[0].reviewItems[0].classificationConfidence").value(0.98))
                .andExpect(jsonPath("$.narrationPlan.chapters[0].reviewItems[0].treatmentConfidence").value(0.93))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Private normal prose"))));

        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID)
                        .header("If-None-Match", "\"1\"")
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(content().string(""));
    }

    @Test
    void conversionProgressRequiresTheOwningListener() throws Exception {
        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID))
                .andExpect(status().isUnauthorized());

        when(conversionService.conversion(LISTENER_ID, CONVERSION_ID))
                .thenThrow(new AudiobookConversionUnavailableException());
        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID)
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSION_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("The Audiobook Conversion is unavailable."));
    }

    @Test
    void frozenReviewProgressDoesNotReExposeTheEditablePlan() throws Exception {
        when(conversionService.conversion(LISTENER_ID, CONVERSION_ID)).thenReturn(
                new AudiobookConversionService.AudiobookConversion(
                        CONVERSION_ID,
                        AudiobookConversionService.ConversionState.AWAITING_REVIEW,
                        "NARRATION_REVIEW_APPROVED",
                        List.of(),
                        2));

        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID)
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("NARRATION_REVIEW_APPROVED"))
                .andExpect(jsonPath("$.allowedActions").isEmpty())
                .andExpect(jsonPath("$.narrationPlan").doesNotExist());
        verifyNoInteractions(narrationPlanService);
    }

    @Test
    void listenerApprovesOnlyBoundedNarrationReviewFieldsAgainstTheSeenVersion() throws Exception {
        UUID decisionId = UUID.fromString("01985f42-5f8d-7000-8000-000000000225");
        when(narrationReviewService.submit(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new NarrationReviewService.ReviewResult(
                        decisionId,
                        NarrationReviewService.ReviewAction.APPROVE,
                        2,
                        false));
        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/narration-review")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "approve-review-25")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"APPROVE",
                                  "sections":[{
                                    "clientId":"section-evidence",
                                    "title":"Evidence and findings",
                                    "excluded":false,
                                    "sourceChapterOrdinals":[0],
                                    "reviewItems":[{
                                      "sourceChapterOrdinal":0,
                                      "ordinal":0,
                                      "treatment":"DESCRIBE",
                                      "narrationSnippet":"A concise description of the evidence table."
                                    }]
                                  }]
                                }
                                """)
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.action").value("APPROVE"))
                .andExpect(jsonPath("$.conversionVersion").value(2));
    }

    @Test
    void staleReviewReturnsAFocusedRecoverableConflictWithTheLatestVersion() throws Exception {
        when(narrationReviewService.submit(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new NarrationReviewRejectedException(
                        NarrationReviewRejectionReason.CONVERSION_VERSION_MISMATCH,
                        3L));

        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/narration-review")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "stale-review-25")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SKIP_OPTIONAL\",\"sections\":[]}")
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSION_VERSION_MISMATCH"))
                .andExpect(jsonPath("$.currentVersion").value(3))
                .andExpect(jsonPath("$.recoverable").value(true))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Reload")));
    }

    @Test
    void publicationProseIsNotAcceptedAsAReviewField() throws Exception {
        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/narration-review")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "prose-review-25")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action":"APPROVE",
                                  "normalProse":"This must never become an editable transcript.",
                                  "sections":[]
                                }
                                """)
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NARRATION_REVIEW"));
    }

    @Test
    void listenerSeesPersistedDamageGuidanceAndCanRequestAnIdempotentSafeRestart() throws Exception {
        var recovery = new AudiobookConversionService.RecoveryDetails(
                7, "Use the saved recovery action after checking the source copy.");
        when(conversionService.conversion(LISTENER_ID, CONVERSION_ID)).thenReturn(
                new AudiobookConversionService.AudiobookConversion(
                        CONVERSION_ID,
                        AudiobookConversionService.ConversionState.PAUSED,
                        "SOURCE_TOO_DAMAGED",
                        List.of(AudiobookConversionService.AllowedAction.RETRY_NARRATION_PLAN),
                        4,
                        recovery));
        when(conversionService.resumeNarrationPlan(LISTENER_ID, CONVERSION_ID, 4, "retry-1"))
                .thenReturn(new AudiobookConversionService.AudiobookConversion(
                        CONVERSION_ID,
                        AudiobookConversionService.ConversionState.PREPARING,
                        "NARRATION_PLAN_PENDING",
                        List.of(),
                        5));

        mockMvc.perform(get("/api/v1/audiobook-conversions/" + CONVERSION_ID)
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"))
                .andExpect(jsonPath("$.recovery.resumeFromPage").value(7))
                .andExpect(jsonPath("$.recovery.listenerGuidance")
                        .value("Use the saved recovery action after checking the source copy."));

        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID
                                + "/narration-plan-recovery")
                        .header("Idempotency-Key", "retry-1")
                        .header("If-Match", "\"4\"")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.state").value("PREPARING"))
                .andExpect(jsonPath("$.reasonCode").value("NARRATION_PLAN_PENDING"));
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal = new ListenerPrincipal(
                LISTENER_ID,
                "Narration Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }
}
