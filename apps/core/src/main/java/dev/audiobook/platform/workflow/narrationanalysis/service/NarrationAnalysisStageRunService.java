package dev.audiobook.platform.workflow.narrationanalysis.service;

import dev.audiobook.platform.worker.*;

import java.util.UUID;

public interface NarrationAnalysisStageRunService {

    int processPending();

    boolean processDelivery(UUID messageId, UUID workId);
}
