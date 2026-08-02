package dev.audiobook.platform.workflow.narrationanalysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.lifecycle.service.ConversionLifecycleService;
import dev.audiobook.platform.workflow.narrationanalysis.persistence.JdbcNarrationAnalysisStageRunRepository;
import dev.audiobook.platform.workflow.narrationanalysis.persistence.JdbcNarrationAnalysisStageRunRepository.DeliveryCoordinates;
import dev.audiobook.platform.workflow.narrationanalysis.persistence.JdbcNarrationAnalysisStageRunRepository.PendingDelivery;
import dev.audiobook.platform.workflow.narrationanalysis.planning.NarrationPlanCreator;
import dev.audiobook.platform.workflow.narrationanalysis.planning.NarrationPlanCreator.CreatedNarrationPlan;
import dev.audiobook.platform.workflow.narrationanalysis.planning.NarrationPlanCreator.SourceTooDamaged;
import dev.audiobook.platform.workflow.narrationanalysis.source.NarrationAnalysisSourceReader;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

class NarrationAnalysisStageRunServiceImplTest {

    private final JdbcNarrationAnalysisStageRunRepository repository =
            mock(JdbcNarrationAnalysisStageRunRepository.class);
    private final NarrationAnalysisSourceReader sourceReader =
            mock(NarrationAnalysisSourceReader.class);
    private final NarrationPlanCreator narrationPlanCreator = mock(NarrationPlanCreator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneOffset.UTC);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final ConversionWorkflowService workflowService = mock(ConversionWorkflowService.class);
    private final ConversionLifecycleService lifecycleService =
            mock(ConversionLifecycleService.class);
    private final PlatformIdentifierGenerator identifierGenerator =
            mock(PlatformIdentifierGenerator.class);
    private NarrationAnalysisStageRunService service;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(repository.expired(clock.instant(), 4, 20)).willReturn(List.of());
        service =
                new NarrationAnalysisStageRunServiceImpl(
                        repository,
                        sourceReader,
                        narrationPlanCreator,
                        clock,
                        transactionManager,
                        workflowService,
                        lifecycleService,
                        identifierGenerator);
    }

    @Test
    void emptyDurableQueueCompletesWithoutReadingPrivateAssets() {
        given(repository.pending(clock.instant(), 4, 20)).willReturn(List.of());

        assertThat(service.processPending()).isZero();

        verifyNoInteractions(sourceReader, narrationPlanCreator);
    }

    @Test
    void selectedPreparingWorkReadsItsSourceAndPreparesExactlyOnePlan() throws Exception {
        PendingDelivery delivery = claimableDelivery();
        byte[] source = new byte[] {1, 2, 3};
        given(sourceReader.read(delivery.submissionId()))
                .willReturn(new ByteArrayInputStream(source));

        assertThat(service.processPending()).isEqualTo(1);

        verify(narrationPlanCreator)
                .create(
                        eq(delivery.listenerId()),
                        eq(delivery.conversionId()),
                        any(ByteArrayInputStream.class));
        verify(repository).succeed(delivery.workId(), delivery.messageId(), clock.instant());
    }

    @Test
    void sourceReadFailureLeavesDurablePreparingWorkForARetry() throws Exception {
        PendingDelivery delivery = claimableDelivery();
        given(sourceReader.read(delivery.submissionId())).willThrow(new IOException("unavailable"));

        assertThatThrownBy(service::processPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan source Working Asset is unavailable");

        verifyNoInteractions(narrationPlanCreator);
        verify(repository).release(delivery.workId(), delivery.messageId(), 4);
    }

    @Test
    void excessivePdfDamagePausesAtTheSafeResumeCheckpointWithoutRetrying() throws Exception {
        PendingDelivery delivery = claimableDelivery();
        given(sourceReader.read(delivery.submissionId()))
                .willReturn(new ByteArrayInputStream(new byte[] {1}));
        doThrow(
                        new SourceTooDamaged(
                                "SOURCE_TOO_DAMAGED",
                                17,
                                "Use a clearer copy.",
                                new IllegalStateException("damaged")))
                .when(narrationPlanCreator)
                .create(
                        eq(delivery.listenerId()),
                        eq(delivery.conversionId()),
                        any(ByteArrayInputStream.class));

        assertThat(service.processPending()).isZero();

        verify(lifecycleService).pause(any(ConversionLifecycleService.PauseCommand.class));
        verify(repository)
                .pause(
                        delivery.workId(),
                        delivery.messageId(),
                        "SOURCE_TOO_DAMAGED",
                        17,
                        "Use a clearer copy.");
    }

    @Test
    void duplicateDeliveryCannotClaimCompletedOrActivelyLeasedWork() {
        PendingDelivery delivery = delivery();
        given(repository.delivery(delivery.messageId(), delivery.workId())).willReturn(delivery);
        given(workflowService.claimDelivery(any()))
                .willReturn(
                        new ConversionWorkflowService.DeliveryDecision(
                                ConversionWorkflowService.DeliveryDisposition.CLAIMED,
                                UUID.randomUUID(),
                                null));
        given(repository.claim(any(), any(), any(), eq(4))).willReturn(false);

        assertThat(service.processDelivery(delivery.messageId(), delivery.workId())).isFalse();

        verifyNoInteractions(sourceReader, narrationPlanCreator);
    }

    private PendingDelivery claimableDelivery() {
        PendingDelivery delivery = delivery();
        given(repository.pending(clock.instant(), 4, 20))
                .willReturn(
                        List.of(new DeliveryCoordinates(delivery.messageId(), delivery.workId())));
        given(repository.delivery(delivery.messageId(), delivery.workId())).willReturn(delivery);
        given(workflowService.claimDelivery(any()))
                .willReturn(
                        new ConversionWorkflowService.DeliveryDecision(
                                ConversionWorkflowService.DeliveryDisposition.CLAIMED,
                                UUID.randomUUID(),
                                null));
        given(repository.claim(any(), any(), any(), eq(4))).willReturn(true);
        given(
                        narrationPlanCreator.create(
                                eq(delivery.listenerId()),
                                eq(delivery.conversionId()),
                                any(ByteArrayInputStream.class)))
                .willReturn(
                        new CreatedNarrationPlan("working/narration/plan.json", "a".repeat(64)));
        given(workflowService.acceptResult(any()))
                .willReturn(
                        new ConversionWorkflowService.ResultDecision(
                                ConversionWorkflowService.ResultDisposition.ACCEPTED,
                                UUID.randomUUID(),
                                null));
        return delivery;
    }

    private static PendingDelivery delivery() {
        return new PendingDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                0L);
    }
}
