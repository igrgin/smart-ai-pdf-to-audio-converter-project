package dev.audiobook.platform.workflow.narrationanalysis.service;

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

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NarrationAnalysisStageRunServiceImpl implements NarrationAnalysisStageRunService {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 4;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(10);

    private final JdbcNarrationAnalysisStageRunRepository repository;
    private final NarrationAnalysisSourceReader sourceReader;
    private final NarrationPlanCreator narrationPlanCreator;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;
    private final ConversionWorkflowService workflowService;
    private final ConversionLifecycleService lifecycleService;
    private final PlatformIdentifierGenerator identifierGenerator;

    @Override
    public int processPending() {
        renewExpiredDeliveries();
        int completed = 0;
        for (DeliveryCoordinates delivery :
                repository.pending(clock.instant(), MAX_ATTEMPTS, BATCH_SIZE)) {
            if (processDelivery(delivery.messageId(), delivery.workId())) {
                completed++;
            }
        }
        return completed;
    }

    @Override
    public boolean processDelivery(UUID messageId, UUID workId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(workId, "workId");
        PendingDelivery delivery = transactions().execute(status -> claim(messageId, workId));
        if (delivery == null) {
            return false;
        }
        try (var publication = sourceReader.read(delivery.submissionId())) {
            CreatedNarrationPlan plan =
                    narrationPlanCreator.create(
                            delivery.listenerId(), delivery.conversionId(), publication);
            transactions().executeWithoutResult(status -> acceptResult(delivery, plan));
            return true;
        } catch (SourceTooDamaged exception) {
            pauseAfterDamage(delivery, exception);
            return false;
        } catch (IOException exception) {
            releaseAfterFailure(delivery);
            throw new IllegalStateException(
                    "Narration Plan source Working Asset is unavailable", exception);
        } catch (RuntimeException exception) {
            releaseAfterFailure(delivery);
            throw exception;
        }
    }

    private PendingDelivery claim(UUID messageId, UUID workId) {
        Instant now = clock.instant();
        PendingDelivery delivery = repository.delivery(messageId, workId);
        ConversionWorkflowService.DeliveryDecision workflowClaim =
                workflowService.claimDelivery(
                        new ConversionWorkflowService.WorkDelivery(
                                messageId,
                                delivery.conversionId(),
                                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                                delivery.schemaVersion(),
                                delivery.expectedConversionVersion(),
                                "narration-worker",
                                LEASE_DURATION));
        if (workflowClaim.disposition() != ConversionWorkflowService.DeliveryDisposition.CLAIMED) {
            return null;
        }
        return repository.claim(delivery, now, now.plus(LEASE_DURATION), MAX_ATTEMPTS)
                ? delivery
                : null;
    }

    private void releaseAfterFailure(PendingDelivery delivery) {
        transactions()
                .executeWithoutResult(
                        status -> {
                            workflowService.failStage(
                                    new ConversionWorkflowService.StageFailure(
                                            delivery.messageId(),
                                            delivery.conversionId(),
                                            ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                                            "NARRATION_ANALYSIS_FAILED",
                                            true));
                            if (repository.release(
                                    delivery.workId(), delivery.messageId(), MAX_ATTEMPTS)) {
                                rotateDelivery(delivery.workId(), delivery.messageId());
                            }
                        });
    }

    private void renewExpiredDeliveries() {
        for (DeliveryCoordinates delivery :
                repository.expired(clock.instant(), MAX_ATTEMPTS, BATCH_SIZE)) {
            rotateDelivery(delivery.workId(), delivery.messageId());
        }
    }

    private void rotateDelivery(UUID workId, UUID previousMessageId) {
        repository.rotate(
                workId, previousMessageId, identifierGenerator.generate(), clock.instant());
    }

    private void pauseAfterDamage(PendingDelivery delivery, SourceTooDamaged exception) {
        transactions()
                .executeWithoutResult(
                        status -> {
                            lifecycleService.pause(
                                    new ConversionLifecycleService.PauseCommand(
                                            delivery.messageId(),
                                            delivery.listenerId(),
                                            delivery.conversionId(),
                                            ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                                            exception.reasonCode(),
                                            ConversionLifecycleService.ResponsibleParty.LISTENER,
                                            null));
                            repository.pause(
                                    delivery.workId(),
                                    delivery.messageId(),
                                    exception.reasonCode(),
                                    exception.resumeFromPage(),
                                    exception.listenerGuidance());
                        });
    }

    private void acceptResult(PendingDelivery delivery, CreatedNarrationPlan plan) {
        ConversionWorkflowService.ResultDecision accepted =
                workflowService.acceptResult(
                        new ConversionWorkflowService.StageResult(
                                delivery.messageId(),
                                delivery.conversionId(),
                                ConversionWorkflowService.Stage.NARRATION_ANALYSIS,
                                "narration-analysis:" + delivery.conversionId(),
                                plan.reference(),
                                plan.digest(),
                                false));
        if (accepted.disposition() != ConversionWorkflowService.ResultDisposition.ACCEPTED
                && accepted.disposition() != ConversionWorkflowService.ResultDisposition.REPLAYED) {
            throw new IllegalStateException("Narration analysis result was not accepted");
        }
        repository.succeed(delivery.workId(), delivery.messageId(), clock.instant());
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }
}
