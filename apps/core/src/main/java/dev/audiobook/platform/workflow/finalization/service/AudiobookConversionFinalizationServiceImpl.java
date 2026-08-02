package dev.audiobook.platform.workflow.finalization.service;

import dev.audiobook.platform.workflow.finalization.persistence.JdbcAudiobookConversionFinalizationRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AudiobookConversionFinalizationServiceImpl
        implements AudiobookConversionFinalizationService {

    private final JdbcAudiobookConversionFinalizationRepository repository;

    @Override
    public void beginFinalizing(UUID listenerId, UUID conversionId) {
        int updated = repository.beginFinalizing(listenerId, conversionId);
        if (updated == 0 && !"FINALIZING".equals(state(listenerId, conversionId))) {
            throw new IllegalStateException("Audiobook Conversion cannot be finalized");
        }
    }

    @Override
    public void lockAndRequireFinalizing(UUID listenerId, UUID conversionId) {
        List<String> states = repository.lockState(listenerId, conversionId);
        if (states.isEmpty() || !"FINALIZING".equals(states.getFirst())) {
            throw new IllegalStateException(
                    "Audiobook Conversion is not ready for visibility-last Finalization");
        }
    }

    @Override
    public void markFinalized(UUID listenerId, UUID conversionId) {
        int updated = repository.markFinalized(listenerId, conversionId);
        if (updated != 1) {
            throw new IllegalStateException("Audiobook Conversion Finalization was lost");
        }
    }

    private String state(UUID listenerId, UUID conversionId) {
        return repository
                .state(listenerId, conversionId)
                .orElseThrow(
                        () -> new IllegalStateException("Audiobook Conversion is unavailable"));
    }
}
