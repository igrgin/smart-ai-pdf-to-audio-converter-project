package dev.audiobook.platform.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeechSegmentationServiceTest {

    private static final UUID CONVERSION_ID =
            UUID.fromString("01985f42-5f8d-7000-8000-000000000029");
    private static final String RECIPE_DIGEST = "a".repeat(64);

    private final SpeechSegmentationService service = new SpeechSegmentationServiceImpl();

    @Test
    void sameApprovedPlanAndRecipeProduceTheSameGaplessManifestAndOperationKeys() {
        List<SpeechSegmentationService.ApprovedChapter> chapters = List.of(
                new SpeechSegmentationService.ApprovedChapter(
                        0,
                        "Opening",
                        List.of(
                                new SpeechSegmentationService.SpokenUnit(
                                        "one two three four", SpeechSegmentationService.BoundaryKind.PARAGRAPH),
                                new SpeechSegmentationService.SpokenUnit(
                                        "five six", SpeechSegmentationService.BoundaryKind.CHAPTER))),
                new SpeechSegmentationService.ApprovedChapter(
                        1,
                        "Closing",
                        List.of(new SpeechSegmentationService.SpokenUnit(
                                "seven", SpeechSegmentationService.BoundaryKind.CHAPTER))));

        SpeechSegmentationService.Manifest first = service.segment(
                new SpeechSegmentationService.SegmentationRequest(
                        CONVERSION_ID, RECIPE_DIGEST, "plan-v1", 10, chapters));
        SpeechSegmentationService.Manifest replay = service.segment(
                new SpeechSegmentationService.SegmentationRequest(
                        CONVERSION_ID, RECIPE_DIGEST, "plan-v1", 10, chapters));

        assertThat(replay).isEqualTo(first);
        assertThat(first.manifestDigest()).matches("[0-9a-f]{64}");
        assertThat(first.segments())
                .extracting(
                        SpeechSegmentationService.Segment::chapterOrdinal,
                        SpeechSegmentationService.Segment::segmentOrdinal,
                        SpeechSegmentationService.Segment::spokenText,
                        SpeechSegmentationService.Segment::boundaryKind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                0, 0, "one two", SpeechSegmentationService.BoundaryKind.LIMIT_CONTINUATION),
                        org.assertj.core.groups.Tuple.tuple(
                                0, 1, "three four", SpeechSegmentationService.BoundaryKind.PARAGRAPH),
                        org.assertj.core.groups.Tuple.tuple(
                                0, 2, "five six", SpeechSegmentationService.BoundaryKind.CHAPTER),
                        org.assertj.core.groups.Tuple.tuple(
                                1, 0, "seven", SpeechSegmentationService.BoundaryKind.CHAPTER));
        assertThat(first.segments())
                .extracting(SpeechSegmentationService.Segment::operationKey)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> assertThat(key)
                        .startsWith("speech:" + CONVERSION_ID + ":")
                        .hasSizeLessThanOrEqualTo(200));
        assertThat(first.segments())
                .extracting(SpeechSegmentationService.Segment::segmentId)
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{64}"));
    }
}
