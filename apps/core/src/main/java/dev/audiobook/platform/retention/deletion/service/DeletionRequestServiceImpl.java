package dev.audiobook.platform.retention.deletion.service;

import dev.audiobook.platform.retention.deletion.DeletionRequest;
import dev.audiobook.platform.retention.deletion.persistence.DeletionRequestPersistence;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeletionRequestServiceImpl implements DeletionRequestService {

    private final DeletionRequestPersistence persistence;

    @Override
    public DeletionRequest.DeletionReceipt deleteAudiobook(
            DeletionRequest.DeleteAudiobookCommand command) {
        return persistence.deleteAudiobook(command);
    }

    @Override
    public DeletionRequest.DeletionReceipt deleteAccount(
            DeletionRequest.DeleteAccountCommand command) {
        return persistence.deleteAccount(command);
    }

    @Override
    public DeletionRequest.DeletionStatus status(UUID listenerId, UUID requestId) {
        return persistence.status(listenerId, requestId);
    }
}
