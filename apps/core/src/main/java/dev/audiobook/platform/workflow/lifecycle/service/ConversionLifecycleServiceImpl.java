package dev.audiobook.platform.workflow.lifecycle.service;

import dev.audiobook.platform.workflow.persistence.JdbcConversionWorkflowPersistence;
import dev.audiobook.platform.workflow.stage.service.ConversionWorkflowService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversionLifecycleServiceImpl implements ConversionLifecycleService {

    private final JdbcConversionWorkflowPersistence persistence;

    @Override
    @Transactional
    public PauseDetails pause(PauseCommand command) {
        return persistence.pause(command);
    }

    @Override
    @Transactional(readOnly = true)
    public PauseDetails pauseDetails(UUID listenerId, UUID conversionId) {
        return persistence.pauseDetails(listenerId, conversionId);
    }

    @Override
    @Transactional
    public ConversionWorkflowService.StageView resume(ResumeCommand command) {
        return persistence.resume(command);
    }

    @Override
    @Transactional
    public CancellationResult cancelListener(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey) {
        return persistence.cancelListener(
                listenerId, conversionId, expectedConversionVersion, idempotencyKey);
    }

    @Override
    @Transactional
    public void recordProviderCost(ProviderCost command) {
        persistence.recordProviderCost(command);
    }
}
