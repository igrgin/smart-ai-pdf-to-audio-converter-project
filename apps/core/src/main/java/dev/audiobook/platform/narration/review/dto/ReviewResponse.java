package dev.audiobook.platform.narration.review.dto;

import dev.audiobook.platform.narration.review.service.NarrationReviewService;

import java.util.UUID;

public record ReviewResponse(
        UUID decisionId, NarrationReviewService.ReviewAction action, long conversionVersion) {}
