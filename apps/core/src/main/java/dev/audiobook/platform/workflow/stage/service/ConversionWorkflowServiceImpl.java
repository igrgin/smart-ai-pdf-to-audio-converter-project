package dev.audiobook.platform.workflow.stage.service;

import dev.audiobook.platform.workflow.persistence.JdbcConversionWorkflowPersistence;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversionWorkflowServiceImpl implements ConversionWorkflowService {

    private final JdbcConversionWorkflowPersistence persistence;

    @Override
    @Transactional
    public StageView scheduleStage(
            UUID listenerId, UUID conversionId, Stage stage, int maximumAttempts) {
        return persistence.scheduleStage(listenerId, conversionId, stage, maximumAttempts);
    }

    @Override
    @Transactional
    public DeliveryDecision claimDelivery(WorkDelivery delivery) {
        return persistence.claimDelivery(delivery);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean claimActive(UUID messageId, UUID conversionId, Stage stage) {
        return persistence.claimActive(messageId, conversionId, stage);
    }

    @Override
    @Transactional
    public ResultDecision acceptResult(StageResult result) {
        return persistence.acceptResult(result);
    }

    @Override
    @Transactional
    public StageView failStage(StageFailure failure) {
        return persistence.failStage(failure);
    }

    @Override
    @Transactional(readOnly = true)
    public StageView stage(UUID listenerId, UUID conversionId, Stage stage) {
        return persistence.stage(listenerId, conversionId, stage);
    }
}
