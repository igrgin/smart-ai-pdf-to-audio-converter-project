package dev.audiobook.platform.admission.inspection.work.service;

import dev.audiobook.platform.admission.inspection.toolchain.InspectionProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

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
            outcomeRecordingService.inspect(
                    new InspectionOutcomeRecordingService.InspectionCommand(
                            inspection.workId(),
                            workerId,
                            now.plus(properties.runtime()),
                            inspection.operationKey()));
        }
        return pending.size();
    }
}
