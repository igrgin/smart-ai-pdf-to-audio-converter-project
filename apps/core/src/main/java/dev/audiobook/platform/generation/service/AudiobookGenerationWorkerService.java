package dev.audiobook.platform.generation.service;

import dev.audiobook.platform.generation.*;

public interface AudiobookGenerationWorkerService {

    int generatePending();

    int packagePending();
}
