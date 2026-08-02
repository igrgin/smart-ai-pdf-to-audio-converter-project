package dev.audiobook.platform.workflow.administration.service;

import dev.audiobook.platform.workflow.lifecycle.service.ConversionLifecycleService;
import dev.audiobook.platform.workflow.persistence.JdbcConversionWorkflowPersistence;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversionWorkflowAdministrationServiceImpl
        implements ConversionWorkflowAdministrationService {

    private final JdbcConversionWorkflowPersistence persistence;

    @Override
    @Transactional
    public ConversionWorkflowService.StageView checkpoint(StageCheckpoint checkpoint) {
        return persistence.checkpoint(checkpoint);
    }

    @Override
    @Transactional
    public ConversionWorkflowService.StageView repairStage(
            UUID listenerId,
            UUID conversionId,
            ConversionWorkflowService.Stage stage,
            long expectedConversionVersion,
            String idempotencyKey) {
        return persistence.repairStage(
                listenerId, conversionId, stage, expectedConversionVersion, idempotencyKey);
    }

    @Override
    @Transactional(readOnly = true)
    public AcceptedResult acceptedResult(UUID listenerId, UUID conversionId, String operationKey) {
        return persistence.acceptedResult(listenerId, conversionId, operationKey);
    }

    @Override
    @Transactional
    public ConversionLifecycleService.CancellationResult failTerminal(
            TerminalFailureCommand command) {
        return persistence.failTerminal(command);
    }

    @Override
    @Transactional(readOnly = true)
    public CleanupObligation cleanup(UUID listenerId, UUID conversionId) {
        return persistence.cleanup(listenerId, conversionId);
    }
}
