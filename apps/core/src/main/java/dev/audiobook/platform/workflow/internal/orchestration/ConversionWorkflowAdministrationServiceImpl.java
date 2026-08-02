package dev.audiobook.platform.workflow.internal.orchestration;

import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversionWorkflowAdministrationServiceImpl
        implements ConversionWorkflowAdministrationService {

    private final JdbcConversionWorkflowAuthority authority;

    @Override
    public ConversionWorkflowService.StageView checkpoint(StageCheckpoint checkpoint) {
        return authority.checkpoint(checkpoint);
    }

    @Override
    public ConversionWorkflowService.StageView repairStage(
            UUID listenerId,
            UUID conversionId,
            ConversionWorkflowService.Stage stage,
            long expectedConversionVersion,
            String idempotencyKey) {
        return authority.repairStage(
                listenerId, conversionId, stage, expectedConversionVersion, idempotencyKey);
    }

    @Override
    public AcceptedResult acceptedResult(UUID listenerId, UUID conversionId, String operationKey) {
        return authority.acceptedResult(listenerId, conversionId, operationKey);
    }

    @Override
    public ConversionLifecycleService.CancellationResult failTerminal(
            TerminalFailureCommand command) {
        return authority.failTerminal(command);
    }

    @Override
    public CleanupObligation cleanup(UUID listenerId, UUID conversionId) {
        return authority.cleanup(listenerId, conversionId);
    }
}
