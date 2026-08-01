package dev.audiobook.platform.workflow;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
