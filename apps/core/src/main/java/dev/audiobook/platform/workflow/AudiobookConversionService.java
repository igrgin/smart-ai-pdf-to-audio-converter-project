package dev.audiobook.platform.workflow;

import dev.audiobook.platform.narration.NarrationSelectionService;
import java.util.List;
import java.util.UUID;

public interface AudiobookConversionService {

    void createPreparing(UUID conversionId, UUID listenerId, UUID sourcePublicationId);

    List<AudiobookConversion> conversions(UUID listenerId);

    NarrationSelectionService.GenerationAuthorization beginSpeechGeneration(UUID listenerId, UUID conversionId);

    record AudiobookConversion(UUID conversionId, ConversionState state) {
    }

    enum ConversionState {
        PREPARING,
        GENERATING
    }
}
