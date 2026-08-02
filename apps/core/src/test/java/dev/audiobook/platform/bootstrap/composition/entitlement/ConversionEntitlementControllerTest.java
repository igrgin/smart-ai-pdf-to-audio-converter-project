package dev.audiobook.platform.bootstrap.composition.entitlement;

import dev.audiobook.platform.entitlement.*;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.internal.submission.PublicationSubmissionService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.identity.internal.session.ListenerIdentityService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class ConversionEntitlementControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000022");

    @MockitoBean
    private ConversionEntitlementService entitlementService;

    @MockitoBean
    private PublicationSubmissionService submissionService;

    @MockitoBean
    private AudiobookConversionService audiobookConversionService;

    @MockitoBean
    private ListenerIdentityService listenerIdentityService;

    @MockitoBean
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlyAnAuthorizedOperatorCanAppendAnApprovalBackedFreeGrant() throws Exception {
        Instant validFrom = Instant.parse("2026-08-01T00:00:00Z");
        when(entitlementService.approveFreeGrant(LISTENER_ID, "approval-case-22", "grant-command-22"))
                .thenReturn(new ConversionEntitlementService.FreeGrant(
                        UUID.fromString("01985f42-5f8d-7000-8000-000000000122"),
                        500_000,
                        validFrom,
                        validFrom.plus(365, ChronoUnit.DAYS),
                        true));

        String path = "/api/v1/operator/listeners/" + LISTENER_ID
                + "/conversion-entitlements/free-grants";
        String body = "{\"approvalReference\":\"approval-case-22\"}";

        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "grant-command-22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "grant-command-22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "grant-command-22")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grantedCharacters").value(500_000))
                .andExpect(jsonPath("$.created").value(true));

        when(entitlementService.approveFreeGrant(LISTENER_ID, "approval-case-22", "second-grant-command"))
                .thenThrow(new IllegalStateException("Listener already has a free Conversion Entitlement"));
        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "second-grant-command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FREE_GRANT_CONFLICT"));

        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "invalid-grant-command")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalReference\":\" \"}")
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path)
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listenerLibraryShowsDerivedAllowanceAndClearNoGrantDenialWithoutItsIdentifier() throws Exception {
        when(entitlementService.allowance(LISTENER_ID))
                .thenReturn(new ConversionEntitlementService.Allowance(
                        ConversionEntitlementService.AllowanceStatus.NO_GRANT,
                        0,
                        0,
                        0,
                        0,
                        "NO_GRANT"));

        mockMvc.perform(get("/api/v1/library").with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionEntitlement.status").value("NO_GRANT"))
                .andExpect(jsonPath("$.conversionEntitlement.availableCharacters").value(0))
                .andExpect(jsonPath("$.conversionEntitlement.canStartConversion").value(false))
                .andExpect(jsonPath("$.conversionEntitlement.denialReason").value("NO_GRANT"))
                .andExpect(jsonPath("$.listenerId").doesNotExist());
    }

    @Test
    void listenerLibraryLabelsTestModeDemonstrationSubscriptionWithoutFinancialClaims() throws Exception {
        when(entitlementService.allowance(LISTENER_ID))
                .thenReturn(new ConversionEntitlementService.Allowance(
                        ConversionEntitlementService.AllowanceStatus.AVAILABLE,
                        500_000,
                        500_000,
                        0,
                        0,
                        null,
                        ConversionEntitlementService.EntitlementSource.DEMONSTRATION_SUBSCRIPTION,
                        ConversionEntitlementService.DemonstrationSubscriptionStatus.CANCEL_AT_PERIOD_END));

        mockMvc.perform(get("/api/v1/library").with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionEntitlement.source").value("DEMONSTRATION_SUBSCRIPTION"))
                .andExpect(jsonPath("$.conversionEntitlement.demonstrationOnly").value(true))
                .andExpect(jsonPath("$.conversionEntitlement.demonstrationSubscriptionStatus")
                        .value("CANCEL_AT_PERIOD_END"));
    }

    @Test
    void onlyAnAuthorizedOperatorCanInspectIndependentProviderSpend() throws Exception {
        when(entitlementService.providerSpend("openai"))
                .thenReturn(new ConversionEntitlementService.ProviderSpend(250_000, 750_000));
        String path = "/api/v1/operator/conversion-entitlements/provider-spend/openai";

        mockMvc.perform(get(path).with(authentication(listenerAuthentication())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(path).with(authentication(operatorAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("openai"))
                .andExpect(jsonPath("$.reservedMicros").value(250_000))
                .andExpect(jsonPath("$.committedMicros").value(750_000));
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal = new ListenerPrincipal(
                LISTENER_ID,
                "Entitled Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }

    private static UsernamePasswordAuthenticationToken operatorAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    }
}
