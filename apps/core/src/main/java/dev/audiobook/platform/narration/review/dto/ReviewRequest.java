package dev.audiobook.platform.narration.review.dto;

import dev.audiobook.platform.narration.review.service.NarrationReviewService;

import java.util.List;

public record ReviewRequest(
        NarrationReviewService.ReviewAction action,
        List<NarrationReviewService.SectionDecision> sections) {}
