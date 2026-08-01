package dev.audiobook.platform.workflow;

import java.util.List;
import java.util.UUID;

public interface AudiobookConversionService {

    void createPreparing(UUID conversionId, UUID listenerId, UUID sourcePublicationId);

    List<AudiobookConversion> conversions(UUID listenerId);

    record AudiobookConversion(UUID conversionId, ConversionState state) {
    }

    enum ConversionState {
        PREPARING
    }
}
