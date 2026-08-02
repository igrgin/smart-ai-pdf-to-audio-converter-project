package dev.audiobook.platform.generation.internal.speech.segmentation;

import dev.audiobook.platform.generation.SpeechBoundaryKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SpeechSegmentationServiceImpl implements SpeechSegmentationService {

    @Override
    public Manifest segment(SegmentationRequest request) {
        validate(request);
        List<UnidentifiedSegment> ordered = new ArrayList<>();
        int expectedChapterOrdinal = 0;
        for (ApprovedChapter chapter : request.chapters()) {
            if (chapter == null || chapter.ordinal() != expectedChapterOrdinal++) {
                throw new IllegalArgumentException("Approved chapters must be gapless and ordered");
            }
            int segmentOrdinal = 0;
            for (SpokenUnit unit : chapter.units()) {
                validate(unit);
                List<String> pieces = split(unit.text(), request.maximumCharacters());
                for (int pieceIndex = 0; pieceIndex < pieces.size(); pieceIndex++) {
                    String text = pieces.get(pieceIndex);
                    SpeechBoundaryKind boundary = pieceIndex + 1 == pieces.size()
                            ? unit.boundaryKind()
                            : SpeechBoundaryKind.LIMIT_CONTINUATION;
                    ordered.add(new UnidentifiedSegment(
                            chapter.ordinal(), segmentOrdinal++, text, sha256(text), boundary));
                }
            }
            if (segmentOrdinal == 0) {
                throw new IllegalArgumentException("Every approved chapter must contain speech");
            }
        }

        String manifestDigest = manifestDigest(
                request.policyVersion(),
                ordered.stream()
                        .map(segment -> new ManifestEntry(
                                segment.chapterOrdinal(),
                                segment.segmentOrdinal(),
                                segment.spokenTextDigest(),
                                segment.boundaryKind(),
                                segment.spokenText().length()))
                        .toList());
        List<Segment> segments = ordered.stream()
                .map(segment -> identify(request, segment))
                .toList();
        return new Manifest(manifestDigest, segments);
    }

    @Override
    public String manifestDigest(String policyVersion, List<ManifestEntry> entries) {
        if (policyVersion == null || policyVersion.isBlank() || entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Persisted segment manifest is invalid");
        }
        if (entries.getFirst() == null
                || entries.getFirst().chapterOrdinal() != 0
                || entries.getFirst().segmentOrdinal() != 0) {
            throw new IllegalArgumentException("Persisted segment manifest must begin at the first chapter");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, policyVersion);
        int chapter = 0;
        int segment = 0;
        for (ManifestEntry entry : entries) {
            if (entry == null
                    || entry.chapterOrdinal() < chapter
                    || entry.chapterOrdinal() > chapter + 1
                    || (entry.chapterOrdinal() == chapter && entry.segmentOrdinal() != segment)
                    || (entry.chapterOrdinal() == chapter + 1 && entry.segmentOrdinal() != 0)
                    || entry.spokenTextDigest() == null
                    || !entry.spokenTextDigest().matches("[0-9a-f]{64}")
                    || entry.boundaryKind() == null
                    || entry.characterCount() < 1) {
                throw new IllegalArgumentException("Persisted segment manifest is not gapless or canonical");
            }
            if (entry.chapterOrdinal() == chapter + 1) {
                chapter++;
                segment = 0;
            }
            append(canonical, Integer.toString(entry.chapterOrdinal()));
            append(canonical, Integer.toString(entry.segmentOrdinal()));
            append(canonical, entry.spokenTextDigest());
            append(canonical, entry.boundaryKind().name());
            append(canonical, Integer.toString(entry.characterCount()));
            segment++;
        }
        return sha256(canonical.toString());
    }

    private static Segment identify(SegmentationRequest request, UnidentifiedSegment segment) {
        String segmentId = sha256(request.recipeDigest()
                + "|" + segment.chapterOrdinal()
                + "|" + segment.segmentOrdinal()
                + "|" + segment.spokenTextDigest());
        String operationKey = "speech:" + request.conversionId()
                + ":" + request.recipeDigest() + ":" + segmentId;
        return new Segment(
                segmentId,
                operationKey,
                segment.chapterOrdinal(),
                segment.segmentOrdinal(),
                segment.spokenText(),
                segment.spokenTextDigest(),
                segment.boundaryKind(),
                segment.spokenText().length());
    }

    private static List<String> split(String text, int maximumCharacters) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (text.length() - start > maximumCharacters) {
            int limit = start + maximumCharacters;
            int boundary = preferredBoundary(text, start, limit, ".?!");
            if (boundary <= start) {
                boundary = preferredBoundary(text, start, limit, ",;:\u2014");
            }
            if (boundary <= start) {
                boundary = lastWhitespace(text, start, limit);
            }
            if (boundary <= start) {
                boundary = safeGraphemeBoundary(text, limit);
            }
            String piece = text.substring(start, boundary).stripTrailing();
            if (piece.isEmpty()) {
                throw new IllegalArgumentException("A spoken unit cannot be segmented safely");
            }
            pieces.add(piece);
            start = boundary;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                start++;
            }
        }
        String finalPiece = text.substring(start).strip();
        if (!finalPiece.isEmpty()) {
            pieces.add(finalPiece);
        }
        return List.copyOf(pieces);
    }

    private static int lastWhitespace(String text, int start, int limit) {
        for (int index = limit; index > start; index--) {
            if (Character.isWhitespace(text.charAt(index - 1))) {
                return index - 1;
            }
        }
        return start;
    }

    private static int preferredBoundary(String text, int start, int limit, String punctuation) {
        for (int index = limit; index > start; index--) {
            char candidate = text.charAt(index - 1);
            if (punctuation.indexOf(candidate) >= 0
                    && (index == text.length() || Character.isWhitespace(text.charAt(index)))) {
                return index;
            }
        }
        return start;
    }

    private static int safeGraphemeBoundary(String text, int limit) {
        BreakIterator graphemes = BreakIterator.getCharacterInstance(Locale.ROOT);
        graphemes.setText(text);
        int boundary = graphemes.preceding(limit + 1);
        return boundary == BreakIterator.DONE ? limit : boundary;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    private static void validate(SegmentationRequest request) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.conversionId(), "conversionId");
        if (request.recipeDigest() == null || !request.recipeDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Recipe digest must be lowercase SHA-256");
        }
        if (request.policyVersion() == null || request.policyVersion().isBlank()) {
            throw new IllegalArgumentException("Segmentation policy version is required");
        }
        if (request.maximumCharacters() < 1 || request.chapters().isEmpty()) {
            throw new IllegalArgumentException("Approved speech and a positive provider limit are required");
        }
    }

    private static void validate(SpokenUnit unit) {
        if (unit == null
                || unit.text() == null
                || unit.text().isBlank()
                || unit.boundaryKind() == null) {
            throw new IllegalArgumentException("Spoken units require text and a boundary");
        }
    }

    public static String sha256(String value) {
        return sha256Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Bytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record UnidentifiedSegment(
            int chapterOrdinal,
            int segmentOrdinal,
            String spokenText,
            String spokenTextDigest,
            SpeechBoundaryKind boundaryKind) {
    }
}
