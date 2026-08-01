package dev.audiobook.platform.generation;

import java.util.List;
import java.util.UUID;

public interface AudiobookGenerationService {

    GenerationManifest prepare(UUID listenerId, UUID conversionId);

    AcceptedSegment generateSegment(UUID listenerId, UUID conversionId, String operationKey);

    PrivateAudiobook finalizeAudiobook(UUID listenerId, UUID conversionId);

    record GenerationManifest(UUID manifestId, String manifestDigest, List<Segment> segments) {
        public GenerationManifest {
            segments = List.copyOf(segments);
        }
    }

    record Segment(
            String segmentId,
            String operationKey,
            int chapterOrdinal,
            int segmentOrdinal,
            String spokenTextDigest,
            SpeechSegmentationService.BoundaryKind boundaryKind) {
    }

    record AcceptedSegment(
            String operationKey,
            UUID attemptId,
            String pcmSha256,
            long durationMs,
            boolean replayed) {
    }

    record PrivateAudiobook(
            UUID audiobookId,
            UUID assetVersionId,
            String availability,
            String manifestDigest,
            long totalDurationMs) {
    }
}
