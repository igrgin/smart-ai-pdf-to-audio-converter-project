package dev.audiobook.platform.generation;

public interface AudiobookGenerationWorkerService {

    int generatePending();

    int packageAndFinalizePending();
}
