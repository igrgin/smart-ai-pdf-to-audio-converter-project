package dev.audiobook.platform.retention.deletion.service;

import dev.audiobook.platform.retention.deletion.DeletionRequest;

import java.util.UUID;

public interface DeletionRequestService {

    DeletionRequest.DeletionReceipt deleteAudiobook(
            DeletionRequest.DeleteAudiobookCommand command);

    DeletionRequest.DeletionReceipt deleteAccount(DeletionRequest.DeleteAccountCommand command);

    DeletionRequest.DeletionStatus status(UUID listenerId, UUID requestId);
}
