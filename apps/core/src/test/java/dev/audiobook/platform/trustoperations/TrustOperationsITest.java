package dev.audiobook.platform.trustoperations;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@AutoConfigureMockMvc
class TrustOperationsITest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant DELEGATED_EXPIRY = NOW.plusSeconds(3600);
    private static final UUID SUPPORT_STAFF = UUID.fromString("01985f42-5f8d-7000-8000-000000000036");
    private static final UUID OTHER_SUPPORT_STAFF = UUID.fromString("01985f42-5f8d-7000-8000-000000000136");
    private static final UUID INCIDENT_RESPONDER = UUID.fromString("01985f42-5f8d-7000-8000-000000000236");
    private static final UUID SECURITY_REVIEWER = UUID.fromString("01985f42-5f8d-7000-8000-000000000336");

    private final MockMvc mockMvc;
    private final TrustOperationsService trustOperationsService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    TrustOperationsITest(
            MockMvc mockMvc,
            TrustOperationsService trustOperationsService,
            JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.trustOperationsService = trustOperationsService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void restoreAuthoritativeFixtures() {
        jdbcTemplate.update(
                "UPDATE narration.narrator_voice SET availability = 'AVAILABLE' WHERE voice_id = ?",
                UUID.fromString("10000000-0000-7000-8000-000000000001"));
        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET access_state = 'QUALIFIED' WHERE profile_id = ?",
                UUID.fromString("20000000-0000-7000-8000-000000000001"));
    }

    @Test
    void roleScopedActionQueueOrdersCasesBySafetyThenExpiryThenUrgency() throws Exception {
        TrustOperationsService.OpenCaseRequest intake = caseRequest(
                TrustOperationsService.CaseType.SUPPORT, 80, NOW.plusSeconds(7200), 10, "SUPPORT_RESTRICTION");
        TrustOperationsService.OperationsCase opened = trustOperationsService.openCase(intake);
        assertThat(trustOperationsService.openCase(intake).caseId()).isEqualTo(opened.caseId());
        assertThatThrownBy(() -> trustOperationsService.openCase(new TrustOperationsService.OpenCaseRequest(
                intake.type(), intake.listenerId(), intake.resourceKind(), intake.resourceId(),
                "DIFFERENT_RESTRICTION", intake.consequenceCode(), intake.deadline(), intake.safetyPriority(),
                intake.urgency(), intake.allowedActions(), intake.correlationId())))
                .isInstanceOf(RuntimeException.class);
        trustOperationsService.openCase(caseRequest(
                TrustOperationsService.CaseType.EXPIRING_ACCESS,
                80,
                NOW.plusSeconds(1800),
                5,
                "ACCESS_EXPIRING"));
        trustOperationsService.openCase(caseRequest(
                TrustOperationsService.CaseType.SUPPORT,
                100,
                NOW.plusSeconds(10800),
                1,
                "SAFETY_REVIEW"));
        trustOperationsService.openCase(caseRequest(
                TrustOperationsService.CaseType.SERVICE_INCIDENT,
                100,
                NOW.plusSeconds(60),
                100,
                "INCIDENT_ONLY"));

        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication("ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].restrictionCode").value("SAFETY_REVIEW"))
                .andExpect(jsonPath("$.cases[1].restrictionCode").value("ACCESS_EXPIRING"))
                .andExpect(jsonPath("$.cases[2].restrictionCode").value("SUPPORT_RESTRICTION"))
                .andExpect(jsonPath("$..listenerId").doesNotExist())
                .andExpect(jsonPath("$..resourceId").doesNotExist());

        UUID voiceId = UUID.fromString("10000000-0000-7000-8000-000000000001");
        try {
            jdbcTemplate.update(
                    "UPDATE narration.narrator_voice SET availability = 'TEMPORARILY_UNAVAILABLE' WHERE voice_id = ?",
                    voiceId);
            mockMvc.perform(get("/api/v1/operator/action-queue")
                            .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_VOICE"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cases[0].type").value("VOICE_AVAILABILITY"))
                    .andExpect(jsonPath("$.cases[0].restrictionCode").value("VOICE_NOT_AVAILABLE"));
        } finally {
            jdbcTemplate.update(
                    "UPDATE narration.narrator_voice SET availability = 'AVAILABLE' WHERE voice_id = ?",
                    voiceId);
        }
        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_VOICE"))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM trust_operations.operations_case WHERE correlation_id = ?",
                Timestamp.class,
                "voice-availability:" + voiceId + ":TEMPORARILY_UNAVAILABLE")).isNotNull();
    }

    @Test
    void listenerApprovalBindsDelegatedAccessToNamedStaffScopeExpiryNotificationAndRevocation() throws Exception {
        UUID listenerId = createListener("delegated-access-listener");
        jdbcTemplate.update(
                "INSERT INTO listener_identity (listener_id, display_name) VALUES (?, ?)",
                SUPPORT_STAFF,
                "Named support staff");
        UUID resourceId = UUID.randomUUID();
        TrustOperationsService.OperationsCase supportCase = trustOperationsService.openCase(
                new TrustOperationsService.OpenCaseRequest(
                        TrustOperationsService.CaseType.SUPPORT,
                        listenerId,
                        "PRIVATE_AUDIOBOOK",
                        resourceId,
                        "PLAYBACK_SUPPORT",
                        "PLAYBACK_REMAINS_UNAVAILABLE",
                        NOW.plusSeconds(7200),
                        60,
                        70,
                        Set.of(TrustOperationsService.PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                        "support-case-36"));
        String casePath = "/api/v1/operator/action-queue/" + supportCase.caseId();

        mockMvc.perform(get(casePath).with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opaqueResourceReference").value(supportCase.opaqueResourceReference().toString()))
                .andExpect(jsonPath("$.authorizedResource").doesNotExist())
                .andExpect(jsonPath("$.auditEvents").isEmpty());

        String requestBody = """
                {
                  "purposeCode":"RESTORE_PLAYBACK",
                  "allowedActions":["VIEW_RESOURCE_REFERENCE"],
                  "expiresAt":"%s"
                }
                """.formatted(DELEGATED_EXPIRY);
        var requestResult = mockMvc.perform(post(casePath + "/delegated-access-requests")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "support-request-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(csrf())
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffDisplayName").value("Named support staff"))
                .andExpect(jsonPath("$.opaqueResourceReference").value(
                        supportCase.opaqueResourceReference().toString()))
                .andExpect(jsonPath("$.purposeCode").value("RESTORE_PLAYBACK"))
                .andExpect(jsonPath("$.listenerId").doesNotExist())
                .andReturn();
        String requestId = com.jayway.jsonpath.JsonPath.read(
                requestResult.getResponse().getContentAsString(), "$.requestId");
        mockMvc.perform(get("/api/v1/support-access-grants")
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests[0].requestId").value(requestId))
                .andExpect(jsonPath("$.pendingRequests[0].staffDisplayName").value("Named support staff"))
                .andExpect(jsonPath("$..listenerId").doesNotExist());

        String approval = "{\"requestId\":\"" + requestId + "\"}";
        var approvalResult = mockMvc.perform(post("/api/v1/support-access-grants")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "listener-approval-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approval)
                        .with(csrf())
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.staffId").value(SUPPORT_STAFF.toString()))
                .andExpect(jsonPath("$.purposeCode").value("RESTORE_PLAYBACK"))
                .andExpect(jsonPath("$.allowedActions[0]").value("VIEW_RESOURCE_REFERENCE"))
                .andExpect(jsonPath("$.expiresAt").value(DELEGATED_EXPIRY.toString()))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String grantId = com.jayway.jsonpath.JsonPath.read(
                approvalResult.getResponse().getContentAsString(), "$.grantId");

        mockMvc.perform(get(casePath).with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource").doesNotExist())
                .andExpect(jsonPath("$.auditEvents[0].actorReference").isNotEmpty())
                .andExpect(jsonPath("$..listenerId").doesNotExist())
                .andExpect(jsonPath("$.auditEvents[0].notificationId").isNotEmpty());
        mockMvc.perform(get(casePath).with(authentication(staffAuthentication(OTHER_SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource").doesNotExist());

        mockMvc.perform(post(casePath + "/actions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "delegated-disclosure-other-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VIEW_RESOURCE_REFERENCE\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(OTHER_SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(casePath + "/actions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "delegated-disclosure-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VIEW_RESOURCE_REFERENCE\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource.kind").value("PRIVATE_AUDIOBOOK"))
                .andExpect(jsonPath("$.authorizedResource.id").value(resourceId.toString()))
                .andExpect(jsonPath("$.auditEvent.authority").value("DELEGATED_SUPPORT_ACCESS"))
                .andExpect(jsonPath("$.auditEvent.action").value("VIEW_RESOURCE_REFERENCE"))
                .andExpect(jsonPath("$.auditEvent.notificationId").isNotEmpty());

        mockMvc.perform(get("/api/v1/support-access-grants")
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[0].grantId").value(grantId))
                .andExpect(jsonPath("$.notifications[*].eventType", org.hamcrest.Matchers.hasItems(
                        "DELEGATED_ACCESS_APPROVED", "PRIVILEGED_ACTION_PERFORMED")));

        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deadline FROM trust_operations.operations_case WHERE correlation_id = ?",
                Timestamp.class,
                "expiring-access:" + grantId).toInstant()).isEqualTo(DELEGATED_EXPIRY);

        mockMvc.perform(post("/api/v1/support-access-grants/" + grantId + "/revocation")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "listener-revocation-36")
                        .header("If-Match", "\"0\"")
                        .with(csrf())
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM trust_operations.operations_case WHERE correlation_id = ?",
                Timestamp.class,
                "expiring-access:" + grantId)).isNotNull();
        mockMvc.perform(get(casePath).with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource").doesNotExist());
        mockMvc.perform(post(casePath + "/actions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "delegated-disclosure-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VIEW_RESOURCE_REFERENCE\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(SUPPORT_STAFF, "ROLE_SUPPORT"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void emergencyAccessRequiresFreshResponderMfaAndCreatesNotificationAuditAndIndependentReview() throws Exception {
        UUID listenerId = createListener("emergency-access-listener");
        UUID resourceId = createAffectedConversion(listenerId);
        UUID profileId = UUID.fromString("20000000-0000-7000-8000-000000000001");
        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET access_state = 'BLOCKED' WHERE profile_id = ?",
                profileId);
        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication(INCIDENT_RESPONDER, "ROLE_INCIDENT_RESPONDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cases[0].type").value("SERVICE_INCIDENT"));
        UUID incidentCaseId = jdbcTemplate.queryForObject(
                "SELECT case_id FROM trust_operations.operations_case WHERE correlation_id = ?",
                UUID.class,
                "service-incident:" + profileId + ":" + resourceId);
        Instant emergencyExpiry = NOW.plusSeconds(1200);
        String emergencyBody = """
                {
                  "incidentReference":"INC-2026-0036",
                  "justificationCode":"RESTORE_SERVICE_SAFELY",
                  "purposeCode":"INCIDENT_DIAGNOSIS",
                  "allowedActions":["VIEW_RESOURCE_REFERENCE"],
                  "expiresAt":"%s"
                }
                """.formatted(emergencyExpiry);
        String emergencyPath = "/api/v1/operator/action-queue/" + incidentCaseId + "/emergency-access";

        mockMvc.perform(post(emergencyPath)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "emergency-grant-36-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emergencyBody)
                        .with(csrf())
                        .with(authentication(staffAuthentication(
                                INCIDENT_RESPONDER,
                                "ROLE_INCIDENT_RESPONDER",
                                NOW.minusSeconds(600)))))
                .andExpect(status().isPreconditionRequired());

        var grantResult = mockMvc.perform(post(emergencyPath)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "emergency-grant-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emergencyBody)
                        .with(csrf())
                        .with(authentication(staffAuthentication(INCIDENT_RESPONDER, "ROLE_INCIDENT_RESPONDER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.responderId").value(INCIDENT_RESPONDER.toString()))
                .andExpect(jsonPath("$.incidentReference").value("INC-2026-0036"))
                .andExpect(jsonPath("$.expiresAt").value(emergencyExpiry.toString()))
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.reviewDueAt").isNotEmpty())
                .andReturn();
        String emergencyGrantId = com.jayway.jsonpath.JsonPath.read(
                grantResult.getResponse().getContentAsString(), "$.grantId");

        mockMvc.perform(get("/api/v1/operator/action-queue/" + incidentCaseId)
                        .with(authentication(staffAuthentication(INCIDENT_RESPONDER, "ROLE_INCIDENT_RESPONDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource").doesNotExist())
                .andExpect(jsonPath("$.auditEvents[0].authority").value("INCIDENT_RESPONDER"))
                .andExpect(jsonPath("$.auditEvents[0].reviewObligation").value("INDEPENDENT_RETROSPECTIVE_REVIEW"))
                .andExpect(jsonPath("$.auditEvents[0].notificationId").isNotEmpty())
                .andExpect(jsonPath("$..listenerId").doesNotExist());
        mockMvc.perform(post("/api/v1/operator/action-queue/" + incidentCaseId + "/actions")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "emergency-disclosure-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"VIEW_RESOURCE_REFERENCE\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(INCIDENT_RESPONDER, "ROLE_INCIDENT_RESPONDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizedResource.id").value(resourceId.toString()))
                .andExpect(jsonPath("$.auditEvent.authority").value("EMERGENCY_ACCESS"))
                .andExpect(jsonPath("$.auditEvent.reviewObligation")
                        .value("INDEPENDENT_RETROSPECTIVE_REVIEW"));
        mockMvc.perform(get("/api/v1/support-access-grants")
                        .with(authentication(listenerAuthentication(listenerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications[*].eventType", org.hamcrest.Matchers.hasItems(
                        "EMERGENCY_ACCESS_STARTED", "PRIVILEGED_ACTION_PERFORMED")));

        String reviewPath = "/api/v1/operator/action-queue/emergency-access-grants/"
                + emergencyGrantId + "/review";
        mockMvc.perform(post(reviewPath)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "emergency-review-self-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROPRIATE\",\"reviewCode\":\"SCOPE_CONFIRMED\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(
                                INCIDENT_RESPONDER,
                                "ROLE_SECURITY_REVIEWER"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(reviewPath)
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "emergency-review-36")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROPRIATE\",\"reviewCode\":\"SCOPE_CONFIRMED\"}")
                        .with(csrf())
                        .with(authentication(staffAuthentication(SECURITY_REVIEWER, "ROLE_SECURITY_REVIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROPRIATE"))
                .andExpect(jsonPath("$.reviewerId").value(SECURITY_REVIEWER.toString()));

        jdbcTemplate.update(
                "UPDATE narration.provider_capability_profile SET access_state = 'QUALIFIED' WHERE profile_id = ?",
                profileId);
        mockMvc.perform(get("/api/v1/operator/action-queue")
                        .with(authentication(staffAuthentication(INCIDENT_RESPONDER, "ROLE_INCIDENT_RESPONDER"))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT resolved_at FROM trust_operations.operations_case WHERE case_id = ?",
                Timestamp.class,
                incidentCaseId)).isNotNull();
    }

    private UUID createAffectedConversion(UUID listenerId) {
        UUID attestationId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        UUID recipeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO admission.rights_attestation (attestation_id, listener_id, terms_version, notice_version, submitted_at) VALUES (?, ?, 'rights-v1', 'notice-v1', ?)",
                attestationId, listenerId, Timestamp.from(NOW));
        jdbcTemplate.update(
                """
                INSERT INTO admission.publication_submission (
                    submission_id, listener_id, attestation_id, entitlement_reservation_id,
                    planned_conversion_id, state, declared_media_type, declared_byte_length,
                    declared_sha256, upload_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ADMITTED', 'application/pdf', 10, ?, ?, ?, ?)
                """,
                submissionId, listenerId, attestationId, UUID.randomUUID(), conversionId,
                "d".repeat(64), Timestamp.from(NOW.plusSeconds(900)), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO admission.source_publication (source_publication_id, listener_id, submission_id, media_type, byte_length, created_at) VALUES (?, ?, ?, 'application/pdf', 10, ?)",
                publicationId, listenerId, submissionId, Timestamp.from(NOW));
        jdbcTemplate.update(
                "INSERT INTO workflow.audiobook_conversion (conversion_id, listener_id, source_publication_id, state, reason_code, created_at) VALUES (?, ?, ?, 'GENERATING', 'SPEECH_PENDING', ?)",
                conversionId, listenerId, publicationId, Timestamp.from(NOW));
        String recipeDigest = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbcTemplate.update(
                """
                INSERT INTO narration.generation_recipe (
                    recipe_id, conversion_id, listener_id, narrator_voice_id, voice_display_name,
                    pace, capability_profile_id, capability_profile_version, provider, service,
                    endpoint, model_snapshot, region, data_policy_version, voice_mapping_id,
                    mapping_version, provider_voice, native_controls, preview_version,
                    evaluation_version, segmentation_policy_version, audio_policy_version,
                    toolchain_version, recipe_digest, created_at
                ) VALUES (?, ?, ?, '10000000-0000-7000-8000-000000000001', 'Rowan',
                    'NATURAL', '20000000-0000-7000-8000-000000000001', 'openai-speech-eu-v1',
                    'openai', 'speech', 'https://eu.api.openai.com/v1/audio/speech',
                    'gpt-4o-mini-tts-2025-12-15', 'eu', 'eu-private-v1',
                    '30000000-0000-7000-8000-000000000001', 'rowan-openai-v1', 'cedar',
                    CAST('{"speed":1.0}' AS jsonb), 'folio-preview-v1', 'speech-eval-2026-08',
                    'segments-v1', 'audio-v1', 'toolchain-v1', ?, ?)
                """,
                recipeId, conversionId, listenerId, recipeDigest, Timestamp.from(NOW));
        jdbcTemplate.update(
                "UPDATE workflow.audiobook_conversion SET current_generation_recipe_id = ? WHERE conversion_id = ?",
                recipeId, conversionId);
        return conversionId;
    }

    private TrustOperationsService.OpenCaseRequest caseRequest(
            TrustOperationsService.CaseType type,
            int safetyPriority,
            Instant deadline,
            int urgency,
        String restrictionCode) {
        UUID listenerId = createListener("Fixture listener");
        return new TrustOperationsService.OpenCaseRequest(
                type,
                listenerId,
                "PRIVATE_AUDIOBOOK",
                UUID.randomUUID(),
                restrictionCode,
                "ACCESS_OR_RECOVERY_DELAYED",
                deadline,
                safetyPriority,
                urgency,
                Set.of(TrustOperationsService.PrivilegedAction.VIEW_RESOURCE_REFERENCE),
                "correlation-" + restrictionCode.toLowerCase(java.util.Locale.ROOT));
    }

    private UUID createListener(String displayName) {
        UUID listenerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO listener_identity (listener_id, display_name) VALUES (?, ?)",
                listenerId,
                displayName);
        return listenerId;
    }

    private static UsernamePasswordAuthenticationToken staffAuthentication(String role) {
        return staffAuthentication(SUPPORT_STAFF, role);
    }

    private static UsernamePasswordAuthenticationToken staffAuthentication(UUID staffId, String role) {
        return staffAuthentication(staffId, role, NOW.minusSeconds(30));
    }

    private static UsernamePasswordAuthenticationToken staffAuthentication(
            UUID staffId,
            String role,
            Instant authenticatedAt) {
        ListenerPrincipal principal = new ListenerPrincipal(
                staffId,
                "Named support staff",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                authenticatedAt);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication(UUID listenerId) {
        ListenerPrincipal principal = new ListenerPrincipal(
                listenerId,
                "Approving Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                NOW.minusSeconds(30));
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }
}
