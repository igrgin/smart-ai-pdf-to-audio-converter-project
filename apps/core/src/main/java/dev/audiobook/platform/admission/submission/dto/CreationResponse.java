package dev.audiobook.platform.admission.submission.dto;

import dev.audiobook.platform.admission.submission.service.PublicationSubmissionService;

import java.util.UUID;

public record CreationResponse(
        UUID submissionId,
        PublicationSubmissionService.SubmissionState state,
        UploadSessionResponse uploadSession,
        boolean created) {}
