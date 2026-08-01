package dev.audiobook.platform.workflow.internal;

import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversionWorkflowServiceImpl implements ConversionWorkflowService {

    private final JdbcConversionWorkflowAuthority authority;

    @Override
    public StageView scheduleStage(UUID listenerId, UUID conversionId, Stage stage, int maximumAttempts) {
        return authority.scheduleStage(listenerId, conversionId, stage, maximumAttempts);
    }

    @Override
    public DeliveryDecision claimDelivery(WorkDelivery delivery) {
        return authority.claimDelivery(delivery);
    }

    @Override
    public boolean claimActive(UUID messageId, UUID conversionId, Stage stage) {
        return authority.claimActive(messageId, conversionId, stage);
    }

    @Override
    public ResultDecision acceptResult(StageResult result) {
        return authority.acceptResult(result);
    }

    @Override
    public StageView failStage(StageFailure failure) {
        return authority.failStage(failure);
    }

    @Override
    public StageView stage(UUID listenerId, UUID conversionId, Stage stage) {
        return authority.stage(listenerId, conversionId, stage);
    }
}
