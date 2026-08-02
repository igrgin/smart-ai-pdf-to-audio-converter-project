package dev.audiobook.platform.generation.packaging.service;

import dev.audiobook.platform.generation.SpeechBoundaryKind;
import dev.audiobook.platform.generation.packaging.*;

import java.util.List;
import java.util.UUID;

public interface AudioPackagingService {

    PackagingResult packageAudiobook(PackagingRequest request);

    record PackagingRequest(
            UUID conversionId,
            String recipeDigest,
            String audioPolicyVersion,
            String toolchainVersion,
            List<Chapter> chapters) {
        public PackagingRequest {
            chapters = chapters == null ? List.of() : List.copyOf(chapters);
        }
    }

    record Chapter(int ordinal, String displayTitle, List<AcceptedPcm> segments) {
        public Chapter {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    record AcceptedPcm(byte[] bytes, SpeechBoundaryKind boundaryKind) {
        public AcceptedPcm {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record PackagingResult(
            String profileVersion,
            List<PackagedChapter> chapters,
            long totalDurationMs,
            long totalBytes,
            double integratedLoudnessLufs,
            double truePeakDbtp,
            double appliedGainDb,
            String manifestDigest) {
        public PackagingResult {
            chapters = List.copyOf(chapters);
        }
    }

    record PackagedChapter(
            int ordinal,
            String displayTitle,
            long startMs,
            long durationMs,
            List<PackagedPart> parts) {
        public PackagedChapter {
            parts = List.copyOf(parts);
        }
    }

    record PackagedPart(
            int ordinal,
            String mimeType,
            byte[] bytes,
            long byteLength,
            long durationMs,
            String sha256) {
        public PackagedPart {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
