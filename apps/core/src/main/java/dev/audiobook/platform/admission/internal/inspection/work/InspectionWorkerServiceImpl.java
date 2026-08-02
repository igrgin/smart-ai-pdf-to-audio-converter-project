package dev.audiobook.platform.admission.internal.inspection.work;

import dev.audiobook.platform.admission.InspectionWorkerService;

import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionProperties;
import dev.audiobook.platform.admission.internal.inspection.work.InspectionOutcomeRecordingService;
import dev.audiobook.platform.admission.internal.inspection.work.InspectionWorkflowService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InspectionWorkerServiceImpl implements InspectionWorkerService {

    private static final int BATCH_SIZE = 10;

    private final InspectionWorkflowService workflowService;
    private final InspectionOutcomeRecordingService outcomeRecordingService;
    private final InspectionProperties properties;
    private final Clock identityClock;
    private final String workerId = "inspection-worker-" + UUID.randomUUID();

    @Override
    public int runPending() {
        Instant now = identityClock.instant();
        var pending = workflowService.pending(now, BATCH_SIZE);
        for (InspectionWorkflowService.PendingInspection inspection : pending) {
            outcomeRecordingService.inspect(new InspectionOutcomeRecordingService.InspectionCommand(
                    inspection.workId(),
                    workerId,
                    now.plus(properties.runtime()),
                    inspection.operationKey()));
        }
        return pending.size();
    }
}
