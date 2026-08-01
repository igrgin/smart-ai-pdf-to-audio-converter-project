package dev.audiobook.platform.workflow.internal;

import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversionLifecycleServiceImpl implements ConversionLifecycleService {

    private final JdbcConversionWorkflowAuthority authority;

    @Override
    public PauseDetails pause(PauseCommand command) {
        return authority.pause(command);
    }

    @Override
    public PauseDetails pauseDetails(UUID listenerId, UUID conversionId) {
        return authority.pauseDetails(listenerId, conversionId);
    }

    @Override
    public ConversionWorkflowService.StageView resume(ResumeCommand command) {
        return authority.resume(command);
    }

    @Override
    public CancellationResult cancelListener(
            UUID listenerId,
            UUID conversionId,
            long expectedConversionVersion,
            String idempotencyKey) {
        return authority.cancelListener(
                listenerId, conversionId, expectedConversionVersion, idempotencyKey);
    }

    @Override
    public void recordProviderCost(ProviderCost command) {
        authority.recordProviderCost(command);
    }

}
