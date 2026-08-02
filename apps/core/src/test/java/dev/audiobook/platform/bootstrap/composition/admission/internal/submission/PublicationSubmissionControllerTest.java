package dev.audiobook.platform.bootstrap.composition.admission.internal.submission;

import dev.audiobook.platform.admission.internal.submission.*;

import dev.audiobook.platform.admission.internal.delivery.AdmissionOutboxRelayService;
import dev.audiobook.platform.admission.internal.delivery.InspectionWorkDeliveryController;
import dev.audiobook.platform.admission.internal.delivery.PubSubPushAuthenticator;
import dev.audiobook.platform.admission.internal.inspection.InspectionOutcomeRecordingService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.internal.session.ListenerIdentityService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.admission.internal.inspection.InspectionWorkflowService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
class PublicationSubmissionControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000023");
    private static final UUID SUBMISSION_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000123");

    @MockitoBean
    private PublicationSubmissionService submissionService;

    @MockitoBean
    private InspectionOutcomeRecordingService inspectionOutcomeRecordingService;

    @MockitoBean
    private AdmissionOutboxRelayService outboxRelayService;

    @MockitoBean
    private PubSubPushAuthenticator pubSubPushAuthenticator;

    @MockitoBean
    private AudiobookConversionService audiobookConversionService;

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
    void authenticatedPubSubPushDurablyAcceptsOpaqueCoordinatesWithoutParsingInTheCore() throws Exception {
        UUID messageId = UUID.fromString("01985f42-5f8d-7000-8000-000000000323");
        UUID workId = UUID.fromString("01985f42-5f8d-7000-8000-000000000423");
        String coordinates = "{\"messageId\":\"%s\",\"workId\":\"%s\"}".formatted(messageId, workId);
        String data = Base64.getEncoder().encodeToString(coordinates.getBytes(StandardCharsets.UTF_8));
        when(pubSubPushAuthenticator.authentic("signed-google-token")).thenReturn(true);

        mockMvc.perform(post(InspectionWorkDeliveryController.DELIVERY_PATH)
                        .header("Authorization", "Bearer signed-google-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":{"data":"%s","attributes":{
                                  "messageType":"INSPECT_SUBMISSION","schemaVersion":"1"
                                }}}
                                """.formatted(data)))
                .andExpect(status().isNoContent());

        verify(inspectionWorkflowService).acceptDelivery(messageId, workId);
        verify(inspectionOutcomeRecordingService, never())
                .inspect(any(InspectionOutcomeRecordingService.InspectionCommand.class));

        when(pubSubPushAuthenticator.authentic("invalid-token")).thenReturn(false);
        mockMvc.perform(post(InspectionWorkDeliveryController.DELIVERY_PATH)
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAudiobookRecordsTheAttestationOnlyAfterAnAuthenticatedExplicitCommand() throws Exception {
        when(submissionService.create(any())).thenReturn(new PublicationSubmissionService.Creation(
                SUBMISSION_ID,
                PublicationSubmissionService.SubmissionState.AWAITING_UPLOAD,
                new PublicationSubmissionService.UploadSession(
                        "upload-secret",
                        Instant.parse("2026-08-01T00:15:00Z"),
                        8_388_608),
                true));
        String body = """
                {
                  "mediaType":"application/epub+zip",
                  "byteLength":1024,
                  "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "rightsAttestation":{"termsVersion":"rights-v1","noticeVersion":"notice-v1"}
                }
                """;

        mockMvc.perform(post("/api/v1/publication-submissions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "submit-epub-23")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/publication-submissions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "submit-epub-23")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/publication-submissions/" + SUBMISSION_ID))
                .andExpect(jsonPath("$.state").value("AWAITING_UPLOAD"))
                .andExpect(jsonPath("$.uploadSession.endpoint")
                        .value("/api/v1/publication-submissions/" + SUBMISSION_ID + "/upload"))
                .andExpect(jsonPath("$.uploadSession.token").value("upload-secret"))
                .andExpect(jsonPath("$.uploadSession.chunkSize").value(8_388_608));

        verify(submissionService).create(new PublicationSubmissionService.CreateCommand(
                LISTENER_ID,
                "application/epub+zip",
                1024,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "rights-v1",
                "notice-v1",
                "submit-epub-23"));
    }

    @Test
    void opaqueUploadCapabilityTransfersAReplaySafeChunkWithoutAListenerSession() throws Exception {
        byte[] chunk = "epub-chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(submissionService.upload(any())).thenReturn(new PublicationSubmissionService.UploadProgress(
                chunk.length, true, "generation-23"));

        mockMvc.perform(put("/api/v1/publication-submissions/" + SUBMISSION_ID + "/upload")
                        .header("Origin", "http://localhost:3000")
                        .header("Upload-Token", "single-purpose-secret")
                        .header("Upload-Offset", "0")
                        .header("Upload-Length", String.valueOf(chunk.length))
                        .header("Upload-Chunk-SHA256", "c".repeat(64))
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextOffset").value(chunk.length))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.storageGeneration").value("generation-23"));

        ArgumentCaptor<PublicationSubmissionService.UploadCommand> command =
                ArgumentCaptor.forClass(PublicationSubmissionService.UploadCommand.class);
        verify(submissionService).upload(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue())
                .extracting(
                        PublicationSubmissionService.UploadCommand::submissionId,
                        PublicationSubmissionService.UploadCommand::token,
                        PublicationSubmissionService.UploadCommand::offset,
                        PublicationSubmissionService.UploadCommand::totalBytes,
                        PublicationSubmissionService.UploadCommand::chunkSha256)
                .containsExactly(SUBMISSION_ID, "single-purpose-secret", 0L, (long) chunk.length, "c".repeat(64));
        org.assertj.core.api.Assertions.assertThat(command.getValue().bytes()).containsExactly(chunk);
    }

    @Test
    void listenerConfirmsStoredEvidenceAndCanPollTheContentFreeSubmissionState() throws Exception {
        when(submissionService.confirm(any())).thenReturn(new PublicationSubmissionService.Submission(
                SUBMISSION_ID, PublicationSubmissionService.SubmissionState.UPLOADED, null, null));
        when(submissionService.submission(LISTENER_ID, SUBMISSION_ID)).thenReturn(
                new PublicationSubmissionService.Submission(
                        SUBMISSION_ID,
                        PublicationSubmissionService.SubmissionState.ADMITTED,
                        null,
                        UUID.fromString("01985f42-5f8d-7000-8000-000000000223")));

        mockMvc.perform(post("/api/v1/publication-submissions/" + SUBMISSION_ID + "/confirm")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "confirm-epub-23")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storageGeneration":"generation-23","byteLength":1024,
                                 "sha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"}
                                """)
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("UPLOADED"));

        mockMvc.perform(get("/api/v1/publication-submissions/" + SUBMISSION_ID)
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ADMITTED"))
                .andExpect(jsonPath("$.conversionId").value("01985f42-5f8d-7000-8000-000000000223"))
                .andExpect(jsonPath("$.filename").doesNotExist());

        verify(submissionService).confirm(new PublicationSubmissionService.ConfirmCommand(
                LISTENER_ID,
                SUBMISSION_ID,
                "generation-23",
                1024,
                "d".repeat(64),
                "confirm-epub-23"));
        verify(outboxRelayService).relayPending();
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal = new ListenerPrincipal(
                LISTENER_ID,
                "EPUB Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }
}
