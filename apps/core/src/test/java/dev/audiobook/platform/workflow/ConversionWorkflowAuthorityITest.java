package dev.audiobook.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
@Transactional
class ConversionWorkflowAuthorityITest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000031");
    private static final UUID CONVERSION_ID = UUID.fromString("01985f42-5f8d-7000-8500-000000000031");

    private final ConversionWorkflowService workflowService;
    private final AudiobookConversionService conversionService;
    private final ConversionEntitlementService entitlementService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ConversionWorkflowAuthorityITest(
            ConversionWorkflowService workflowService,
            AudiobookConversionService conversionService,
            ConversionEntitlementService entitlementService,
            JdbcTemplate jdbcTemplate) {
        this.workflowService = workflowService;
        this.conversionService = conversionService;
        this.entitlementService = entitlementService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void seedConversion() {
        jdbcTemplate.update("INSERT INTO listener_identity (listener_id, display_name) VALUES (?, ?)",
                LISTENER_ID, "Workflow Listener");
        jdbcTemplate.update("""
                INSERT INTO admission.rights_attestation (
                    attestation_id, listener_id, terms_version, notice_version, submitted_at
                ) VALUES (?, ?, 'rights-v1', 'notice-v1', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), LISTENER_ID);
        UUID attestationId = jdbcTemplate.queryForObject(
                "SELECT attestation_id FROM admission.rights_attestation WHERE listener_id = ?",
                UUID.class,
                LISTENER_ID);
        UUID submissionId = UUID.randomUUID();
        UUID sourcePublicationId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO admission.publication_submission (
                    submission_id, listener_id, attestation_id, entitlement_reservation_id,
                    planned_conversion_id, state, declared_media_type, declared_byte_length,
                    declared_sha256, upload_expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ADMITTED', 'application/epub+zip', 100,
                          ?, CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, submissionId, LISTENER_ID, attestationId, UUID.randomUUID(), CONVERSION_ID, "a".repeat(64));
        jdbcTemplate.update("""
                INSERT INTO admission.source_publication (
                    source_publication_id, listener_id, submission_id, media_type, byte_length, created_at
                ) VALUES (?, ?, ?, 'application/epub+zip', 100, CURRENT_TIMESTAMP)
                """, sourcePublicationId, LISTENER_ID, submissionId);
        jdbcTemplate.update("""
                INSERT INTO workflow.audiobook_conversion (
                    conversion_id, listener_id, source_publication_id, state, reason_code, created_at
                ) VALUES (?, ?, ?, 'PREPARING', 'NARRATION_PLAN_PENDING', CURRENT_TIMESTAMP)
                """, CONVERSION_ID, LISTENER_ID, sourcePublicationId);
        entitlementService.approveFreeGrant(LISTENER_ID, "workflow-approval-31", "workflow-grant-31");
        ConversionEntitlementService.AdmissionDecision reservation = entitlementService.authorizeSpeech(
                new ConversionEntitlementService.AdmissionRequest(
                        LISTENER_ID,
                        CONVERSION_ID,
                        "openai",
                        "workflow-recipe-31",
                        "workflow-rates-31",
                        100_000,
                        1_000_000,
                        "workflow-reservation-31"));
        assertThat(reservation.authorized()).isTrue();
        jdbcTemplate.update(
                "UPDATE admission.publication_submission SET entitlement_reservation_id = ? WHERE submission_id = ?",
                reservation.reservationId(),
                submissionId);
    }

    @Test
    void duplicateStaleAndUnknownDeliveriesNeverAdvanceAuthoritativeStageState() {
        workflowService.scheduleStage(
                LISTENER_ID,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                2);

        var unknownSchema = workflowService.claimDelivery(new ConversionWorkflowService.WorkDelivery(
                UUID.randomUUID(),
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                2,
                0,
                "narration-worker-a",
                Duration.ofMinutes(10)));
        var stale = workflowService.claimDelivery(new ConversionWorkflowService.WorkDelivery(
                UUID.randomUUID(),
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                1,
                9,
                "narration-worker-a",
                Duration.ofMinutes(10)));
        UUID acceptedMessageId = UUID.randomUUID();
        var accepted = workflowService.claimDelivery(new ConversionWorkflowService.WorkDelivery(
                acceptedMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                1,
                0,
                "narration-worker-a",
                Duration.ofMinutes(10)));
        workflowService.checkpoint(new ConversionWorkflowService.StageCheckpoint(
                acceptedMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                "working/narration/checkpoints/page-12.json",
                "e".repeat(64)));
        var duplicate = workflowService.claimDelivery(new ConversionWorkflowService.WorkDelivery(
                acceptedMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                1,
                0,
                "narration-worker-a",
                Duration.ofMinutes(10)));

        assertThat(unknownSchema.disposition())
                .isEqualTo(ConversionWorkflowService.DeliveryDisposition.DEAD_LETTERED);
        assertThat(stale.disposition()).isEqualTo(ConversionWorkflowService.DeliveryDisposition.STALE);
        assertThat(accepted.disposition()).isEqualTo(ConversionWorkflowService.DeliveryDisposition.CLAIMED);
        assertThat(duplicate.disposition()).isEqualTo(ConversionWorkflowService.DeliveryDisposition.DUPLICATE);
        assertThat(workflowService.stage(LISTENER_ID, CONVERSION_ID,
                        ConversionWorkflowService.Stage.NARRATION_ANALYSIS))
                .extracting(
                        ConversionWorkflowService.StageView::state,
                        ConversionWorkflowService.StageView::attemptCount,
                        ConversionWorkflowService.StageView::leaseOwner)
                .containsExactly(
                        ConversionWorkflowService.StageState.CLAIMED,
                        1,
                        "narration-worker-a");
        assertThat(workflowService.stage(LISTENER_ID, CONVERSION_ID,
                        ConversionWorkflowService.Stage.NARRATION_ANALYSIS))
                .extracting(
                        ConversionWorkflowService.StageView::checkpointReference,
                        ConversionWorkflowService.StageView::checkpointDigest)
                .containsExactly("working/narration/checkpoints/page-12.json", "e".repeat(64));
    }

    @Test
    void repairingAssemblyPreservesAcceptedProviderWorkAndReopensOnlyAssembly() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH, 2);
        UUID speechMessageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                speechMessageId, ConversionWorkflowService.Stage.SPEECH, "speech-worker"));
        var acceptedSpeech = workflowService.acceptResult(new ConversionWorkflowService.StageResult(
                speechMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.SPEECH,
                "speech:" + CONVERSION_ID + ":segment-0",
                "working/audio/segment-0.pcm",
                "b".repeat(64),
                true));

        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.ASSEMBLY, 2);
        UUID assemblyMessageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                assemblyMessageId, ConversionWorkflowService.Stage.ASSEMBLY, "assembly-worker"));
        workflowService.failStage(new ConversionWorkflowService.StageFailure(
                assemblyMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.ASSEMBLY,
                "ASSEMBLY_HASH_MISMATCH",
                false));

        var repaired = workflowService.repairStage(
                LISTENER_ID,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.ASSEMBLY,
                0,
                "repair-assembly-31");
        var replayedSpeech = workflowService.acceptedResult(
                LISTENER_ID,
                CONVERSION_ID,
                "speech:" + CONVERSION_ID + ":segment-0");
        var speechReplayClaim = workflowService.claimDelivery(delivery(
                UUID.randomUUID(), ConversionWorkflowService.Stage.SPEECH, "speech-worker"));

        assertThat(acceptedSpeech.disposition())
                .isEqualTo(ConversionWorkflowService.ResultDisposition.ACCEPTED);
        assertThat(replayedSpeech.resultDigest()).isEqualTo("b".repeat(64));
        assertThat(replayedSpeech.providerWork()).isTrue();
        assertThat(workflowService.stage(
                        LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH).state())
                .isEqualTo(ConversionWorkflowService.StageState.SUCCEEDED);
        assertThat(repaired)
                .extracting(
                        ConversionWorkflowService.StageView::stage,
                        ConversionWorkflowService.StageView::state,
                        ConversionWorkflowService.StageView::attemptCount)
                .containsExactly(
                        ConversionWorkflowService.Stage.ASSEMBLY,
                        ConversionWorkflowService.StageState.READY,
                        1);
        assertThat(speechReplayClaim.disposition())
                .isEqualTo(ConversionWorkflowService.DeliveryDisposition.REJECTED);
    }

    @Test
    void pausePersistsSafeResumeContextAndResumeRevalidatesCurrentEligibility() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.NARRATION_ANALYSIS, 3);
        UUID messageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                messageId, ConversionWorkflowService.Stage.NARRATION_ANALYSIS, "analysis-worker"));
        Instant deadline = Instant.now().plus(Duration.ofDays(7));

        var paused = workflowService.pause(new ConversionWorkflowService.PauseCommand(
                messageId,
                LISTENER_ID,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                "PROVIDER_RESULT_AMBIGUOUS",
                ConversionWorkflowService.ResponsibleParty.PROVIDER,
                deadline));
        var visiblePause = workflowService.pauseDetails(LISTENER_ID, CONVERSION_ID);
        var resumed = workflowService.resume(new ConversionWorkflowService.ResumeCommand(
                LISTENER_ID, CONVERSION_ID, 1, "resume-provider-ambiguity-31"));
        var replay = workflowService.resume(new ConversionWorkflowService.ResumeCommand(
                LISTENER_ID, CONVERSION_ID, 1, "resume-provider-ambiguity-31"));

        assertThat(paused)
                .extracting(
                        ConversionWorkflowService.PauseDetails::reasonCode,
                        ConversionWorkflowService.PauseDetails::responsibleParty,
                        ConversionWorkflowService.PauseDetails::safeResumeStage,
                        ConversionWorkflowService.PauseDetails::deadline)
                .containsExactly(
                        "PROVIDER_RESULT_AMBIGUOUS",
                        ConversionWorkflowService.ResponsibleParty.PROVIDER,
                        ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                        deadline);
        assertThat(visiblePause).isEqualTo(paused);
        assertThat(resumed.state()).isEqualTo(ConversionWorkflowService.StageState.READY);
        assertThat(replay).isEqualTo(resumed);
        assertThat(conversionService.conversion(LISTENER_ID, CONVERSION_ID))
                .extracting(
                        AudiobookConversionService.AudiobookConversion::state,
                        AudiobookConversionService.AudiobookConversion::version)
                .containsExactly(AudiobookConversionService.ConversionState.PREPARING, 2L);
    }

    @Test
    void resumeLeavesTheConversionPausedWhenCurrentEntitlementIsNoLongerEligible() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.NARRATION_ANALYSIS, 3);
        UUID messageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                messageId, ConversionWorkflowService.Stage.NARRATION_ANALYSIS, "analysis-worker"));
        workflowService.pause(new ConversionWorkflowService.PauseCommand(
                messageId,
                LISTENER_ID,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                "POLICY_REVALIDATION_REQUIRED",
                ConversionWorkflowService.ResponsibleParty.PLATFORM,
                null));
        UUID reservationId = jdbcTemplate.queryForObject(
                """
                SELECT reservation_id FROM character_entitlement_ledger_entry
                WHERE listener_id = ? AND conversion_id = ? AND entry_type = 'RESERVATION'
                """,
                UUID.class,
                LISTENER_ID,
                CONVERSION_ID);
        entitlementService.settle(new ConversionEntitlementService.SettlementRequest(
                reservationId, 0, 0, "settle-before-resume-31"));

        assertThatThrownBy(() -> workflowService.resume(new ConversionWorkflowService.ResumeCommand(
                        LISTENER_ID, CONVERSION_ID, 1, "resume-ineligible-31")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy rejected");
        assertThat(workflowService.stage(
                        LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.NARRATION_ANALYSIS).state())
                .isEqualTo(ConversionWorkflowService.StageState.PAUSED);
        assertThat(conversionService.conversion(LISTENER_ID, CONVERSION_ID).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PAUSED);
    }

    @Test
    void speechResumeLeavesTheConversionPausedWhenTheRecipeIsNoLongerEligible() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH, 3);
        UUID messageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                messageId, ConversionWorkflowService.Stage.SPEECH, "speech-worker"));
        workflowService.pause(new ConversionWorkflowService.PauseCommand(
                messageId,
                LISTENER_ID,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.SPEECH,
                "RECIPE_REVALIDATION_REQUIRED",
                ConversionWorkflowService.ResponsibleParty.PLATFORM,
                null));

        assertThatThrownBy(() -> workflowService.resume(new ConversionWorkflowService.ResumeCommand(
                        LISTENER_ID, CONVERSION_ID, 1, "resume-ineligible-recipe-31")))
                .isInstanceOf(RuntimeException.class);
        assertThat(workflowService.stage(
                        LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH).state())
                .isEqualTo(ConversionWorkflowService.StageState.PAUSED);
        assertThat(conversionService.conversion(LISTENER_ID, CONVERSION_ID).state())
                .isEqualTo(AudiobookConversionService.ConversionState.PAUSED);
    }

    @Test
    void cancellationStopsClaimsRejectsLateResultsAndSettlesLedgersIndependently() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH, 3);
        UUID inFlightMessageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                inFlightMessageId, ConversionWorkflowService.Stage.SPEECH, "speech-worker"));

        workflowService.recordProviderCost(new ConversionWorkflowService.ProviderCost(
                LISTENER_ID,
                CONVERSION_ID,
                750_000,
                "provider-request:cancellation-cost-31",
                "provider-cost:cancellation-31"));
        var cancelled = workflowService.cancelListener(
                LISTENER_ID, CONVERSION_ID, 0, "cancel-conversion-31");
        var replay = workflowService.cancelListener(
                LISTENER_ID, CONVERSION_ID, 0, "cancel-conversion-31");
        var lateClaim = workflowService.claimDelivery(delivery(
                UUID.randomUUID(), ConversionWorkflowService.Stage.SPEECH, "speech-worker"));
        var lateResult = workflowService.acceptResult(new ConversionWorkflowService.StageResult(
                inFlightMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.SPEECH,
                "speech:" + CONVERSION_ID + ":late-segment",
                "working/audio/late-segment.pcm",
                "c".repeat(64),
                true));

        assertThat(cancelled.state()).isEqualTo(AudiobookConversionService.ConversionState.CANCELLED);
        assertThat(replay).isEqualTo(cancelled);
        assertThat(lateClaim.disposition()).isEqualTo(ConversionWorkflowService.DeliveryDisposition.LATE);
        assertThat(lateResult.disposition()).isEqualTo(ConversionWorkflowService.ResultDisposition.LATE);
        assertThat(workflowService.stage(
                        LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.SPEECH).state())
                .isEqualTo(ConversionWorkflowService.StageState.CANCELLED);
        assertThat(entitlementService.allowance(LISTENER_ID))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(500_000L, 0L, 0L);
        assertThat(entitlementService.providerSpend("openai"))
                .extracting(
                        ConversionEntitlementService.ProviderSpend::reservedMicros,
                        ConversionEntitlementService.ProviderSpend::committedMicros)
                .containsExactly(0L, 750_000L);
        assertThat(workflowService.cleanup(LISTENER_ID, CONVERSION_ID))
                .extracting(
                        ConversionWorkflowService.CleanupObligation::state,
                        ConversionWorkflowService.CleanupObligation::reasonCode)
                .containsExactly(ConversionWorkflowService.CleanupState.PENDING, "LISTENER_CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM library.private_audiobook WHERE conversion_id = ?",
                Integer.class,
                CONVERSION_ID)).isZero();
    }

    @Test
    void terminalFailureHidesPartialOutputRestoresCharactersAndRetainsProviderCost() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.PACKAGING, 2);
        UUID inFlightMessageId = UUID.randomUUID();
        workflowService.claimDelivery(delivery(
                inFlightMessageId, ConversionWorkflowService.Stage.PACKAGING, "packaging-worker"));

        var failed = workflowService.failTerminal(new ConversionWorkflowService.TerminalFailureCommand(
                LISTENER_ID,
                CONVERSION_ID,
                0,
                "PACKAGING_RESULT_INVALID",
                0,
                600_000,
                "terminal-packaging-failure-31"));
        var lateResult = workflowService.acceptResult(new ConversionWorkflowService.StageResult(
                inFlightMessageId,
                CONVERSION_ID,
                ConversionWorkflowService.Stage.PACKAGING,
                "packaging:" + CONVERSION_ID,
                "working/audio/late-package.json",
                "d".repeat(64),
                false));

        assertThat(failed.state()).isEqualTo(AudiobookConversionService.ConversionState.FAILED);
        assertThat(failed.reasonCode()).isEqualTo("PACKAGING_RESULT_INVALID");
        assertThat(lateResult.disposition()).isEqualTo(ConversionWorkflowService.ResultDisposition.LATE);
        assertThat(entitlementService.allowance(LISTENER_ID))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(500_000L, 0L, 0L);
        assertThat(entitlementService.providerSpend("openai"))
                .extracting(
                        ConversionEntitlementService.ProviderSpend::reservedMicros,
                        ConversionEntitlementService.ProviderSpend::committedMicros)
                .containsExactly(0L, 600_000L);
        assertThat(workflowService.cleanup(LISTENER_ID, CONVERSION_ID).reasonCode())
                .isEqualTo("TERMINAL_FAILURE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM library.private_audiobook WHERE conversion_id = ?",
                Integer.class,
                CONVERSION_ID)).isZero();
    }

    @Test
    void terminalFailureCommitsCharactersBackedByAReusableResult() {
        workflowService.scheduleStage(
                LISTENER_ID, CONVERSION_ID, ConversionWorkflowService.Stage.PACKAGING, 2);

        workflowService.failTerminal(new ConversionWorkflowService.TerminalFailureCommand(
                LISTENER_ID,
                CONVERSION_ID,
                0,
                "FINALIZATION_PUBLICATION_FAILED",
                100_000,
                600_000,
                "terminal-reusable-failure-31"));

        assertThat(entitlementService.allowance(LISTENER_ID))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::reservedCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(400_000L, 0L, 100_000L);
        assertThat(entitlementService.providerSpend("openai").committedMicros())
                .isEqualTo(600_000L);
    }

    private static ConversionWorkflowService.WorkDelivery delivery(
            UUID messageId,
            ConversionWorkflowService.Stage stage,
            String workerId) {
        return new ConversionWorkflowService.WorkDelivery(
                messageId,
                CONVERSION_ID,
                stage,
                1,
                0,
                workerId,
                Duration.ofMinutes(10));
    }
}
