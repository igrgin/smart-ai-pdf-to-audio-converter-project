package dev.audiobook.platform.offline.authorization;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.submission.service.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ledger.service.ConversionEntitlementService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.identity.listener.service.ListenerIdentityService;
import dev.audiobook.platform.narration.selection.service.NarrationSelectionService;
import dev.audiobook.platform.offline.authorization.service.OfflineAccessService;
import dev.audiobook.platform.workflow.conversion.service.AudiobookConversionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class OfflineAccessControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000033");
    private static final UUID INSTALLATION_ID =
            UUID.fromString("01985f42-5f8d-7000-8000-000000000133");
    private static final UUID AUDIOBOOK_ID =
            UUID.fromString("01985f42-5f8d-7000-8000-000000000233");
    private static final UUID ASSET_VERSION_ID =
            UUID.fromString("01985f42-5f8d-7000-8000-000000000333");
    private static final String PATH =
            "/api/v1/audiobooks/"
                    + AUDIOBOOK_ID
                    + "/asset-versions/"
                    + ASSET_VERSION_ID
                    + "/offline-copy-authorizations";

    @MockitoBean private OfflineAccessService offlineAccessService;

    @MockitoBean private ListenerIdentityService listenerIdentityService;

    @MockitoBean private ConversionEntitlementService conversionEntitlementService;

    @MockitoBean private PublicationSubmissionService publicationSubmissionService;

    @MockitoBean private AudiobookConversionService audiobookConversionService;

    @MockitoBean private NarrationSelectionService narrationSelectionService;

    @MockitoBean private DataSource dataSource;

    @Autowired private MockMvc mockMvc;

    @Test
    void forwardsAuthenticatedConditionalMutationAndReturnsNoStoreAuthorization() throws Exception {
        Instant issuedAt = Instant.parse("2026-08-01T12:00:00Z");
        OfflineAccessService.IssueAuthorization command =
                new OfflineAccessService.IssueAuthorization(
                        LISTENER_ID, INSTALLATION_ID, AUDIOBOOK_ID, ASSET_VERSION_ID, "offline-33");
        OfflineAccessService.AuthorizationClaims claims =
                new OfflineAccessService.AuthorizationClaims(
                        LISTENER_ID,
                        INSTALLATION_ID,
                        AUDIOBOOK_ID,
                        ASSET_VERSION_ID,
                        1,
                        "OFFLINE_PLAYBACK",
                        issuedAt,
                        issuedAt.plusSeconds(30L * 24 * 60 * 60));
        when(offlineAccessService.issue(command))
                .thenReturn(
                        new OfflineAccessService.OfflineCopyAuthorization(
                                issuedAt,
                                new OfflineAccessService.SignedAuthorization(
                                        "ES256",
                                        "offline-v1",
                                        "public",
                                        "payload",
                                        "signature",
                                        claims),
                                new OfflineAccessService.OfflineManifest(
                                        AUDIOBOOK_ID,
                                        ASSET_VERSION_ID,
                                        "a".repeat(64),
                                        "application/pdf",
                                        "Rowan",
                                        10_000,
                                        8,
                                        List.of(),
                                        List.of())));

        mockMvc.perform(
                        post(PATH)
                                .header("Origin", "http://localhost:3000")
                                .header("Idempotency-Key", "offline-33")
                                .contentType("application/json")
                                .content("{\"installationId\":\"" + INSTALLATION_ID + "\"}")
                                .with(csrf())
                                .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        jsonPath("$.authorization.claims.listenerId").value(LISTENER_ID.toString()))
                .andExpect(
                        jsonPath("$.manifest.assetVersionId").value(ASSET_VERSION_ID.toString()));
        verify(offlineAccessService).issue(command);
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal =
                new ListenerPrincipal(
                        LISTENER_ID,
                        "Offline Listener",
                        null,
                        Set.of(SignInProvider.GOOGLE),
                        SignInProvider.GOOGLE,
                        Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(principal, "session", Set.of());
    }
}
