package dev.audiobook.platform.narration.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationPlanConversionAccess;
import dev.audiobook.platform.narration.extraction.AdmittedPublicationNarrationPlanInterpreter;
import dev.audiobook.platform.narration.extraction.epub.EpubNarrationPlanInterpreter;
import dev.audiobook.platform.narration.planning.assets.NarrationPlanAssetIdentity;
import dev.audiobook.platform.narration.planning.persistence.JdbcNarrationPlanRepository;
import dev.audiobook.platform.narration.planning.persistence.JdbcNarrationPlanRepository.StoredPlan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

class NarrationPlanServiceImplTest {

    private final AdmittedPublicationNarrationPlanInterpreter interpreter =
            mock(AdmittedPublicationNarrationPlanInterpreter.class);
    private final NarrationPlanAssetStore assetStore = mock(NarrationPlanAssetStore.class);
    private final JdbcNarrationPlanRepository repository = mock(JdbcNarrationPlanRepository.class);
    private final PlatformIdentifierGenerator identifierGenerator =
            mock(PlatformIdentifierGenerator.class);
    private final NarrationPlanConversionAccess conversionAccess =
            mock(NarrationPlanConversionAccess.class);
    private final Clock clock = mock(Clock.class);
    private NarrationPlanService service;

    @BeforeEach
    void setUp() {
        service =
                new NarrationPlanServiceImpl(
                        interpreter,
                        assetStore,
                        repository,
                        identifierGenerator,
                        conversionAccess,
                        clock);
    }

    @Test
    void completedConversionReplayDoesNotReadOrRewritePrivateAssets() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(conversionAccess.awaitingReview(listenerId, conversionId)).willReturn(true);

        service.prepare(listenerId, conversionId, new ByteArrayInputStream(new byte[] {1}));

        verifyNoInteractions(interpreter, assetStore, repository, identifierGenerator, clock);
    }

    @Test
    void relationalPlanReplayDoesNotReadOrRewritePrivateAssets() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(repository.exists(listenerId, conversionId)).willReturn(true);

        service.prepare(listenerId, conversionId, new ByteArrayInputStream(new byte[] {1}));

        verifyNoInteractions(interpreter, assetStore, identifierGenerator, clock);
    }

    @Test
    void confirmsOnlyCandidateConversionsWithNarrationOwnedPlans() {
        UUID planned = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        given(repository.existingConversionIds(List.of(planned, missing)))
                .willReturn(List.of(planned));

        assertThat(service.existingPlanConversionIds(List.of(planned, missing)))
                .containsExactly(planned);
        assertThat(service.existingPlanConversionIds(List.of())).isEmpty();

        verify(repository).existingConversionIds(List.of(planned, missing));
    }

    @Test
    void workingAssetFailureLeavesThePlanUncommittedForRetry() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(repository.exists(listenerId, conversionId)).willReturn(false);
        given(interpreter.interpret(any())).willReturn(plan());
        given(assetStore.write(eq(conversionId), any(byte[].class)))
                .willThrow(new IOException("unavailable"));

        assertThatThrownBy(
                        () ->
                                service.prepare(
                                        listenerId,
                                        conversionId,
                                        new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan Working Asset storage is unavailable");

        verifyNoInteractions(identifierGenerator, clock);
    }

    @Test
    void missingOrWrongSchemaPlanIsNotExposed() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(repository.plans(listenerId, conversionId)).willReturn(List.of());

        assertThatThrownBy(() -> service.plan(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan is not ready");

        stubStoredPlan(listenerId, conversionId, "ref", "digest", "future-schema");
        assertThatThrownBy(() -> service.plan(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan is not ready");
    }

    @Test
    void planReadDependencyIntegrityAndSchemaFailuresRemainDistinct() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        String reference = NarrationPlanAssetIdentity.reference(conversionId);
        stubStoredPlan(listenerId, conversionId, reference, "not-the-digest", "narration-plan-v1");
        given(assetStore.read(conversionId, reference)).willThrow(new IOException("unavailable"));

        assertThatThrownBy(() -> service.plan(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan Working Asset storage is unavailable");

        byte[] invalidPlan = "not-json".getBytes(StandardCharsets.UTF_8);
        doReturn(invalidPlan).when(assetStore).read(conversionId, reference);
        assertThatThrownBy(() -> service.plan(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan Working Asset integrity check failed");

        stubStoredPlan(
                listenerId,
                conversionId,
                reference,
                NarrationPlanAssetIdentity.sha256(invalidPlan),
                "narration-plan-v1");
        assertThatThrownBy(() -> service.plan(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan schema validation failed");
    }

    private void stubStoredPlan(
            UUID listenerId,
            UUID conversionId,
            String reference,
            String digest,
            String schemaVersion)
            throws Exception {
        given(repository.plans(listenerId, conversionId))
                .willReturn(List.of(new StoredPlan(reference, digest, schemaVersion)));
    }

    private static EpubNarrationPlanInterpreter.NarrationPlan plan() {
        var provenance =
                new EpubNarrationPlanInterpreter.StructuralProvenance(
                        EpubNarrationPlanInterpreter.ProvenanceSource.EPUB_SPINE,
                        0,
                        "OPS/chapter.xhtml",
                        null,
                        true,
                        new EpubNarrationPlanInterpreter.Confidence(1.0));
        return new EpubNarrationPlanInterpreter.NarrationPlan(
                List.of(
                        new EpubNarrationPlanInterpreter.Chapter(
                                0, "Chapter", provenance, List.of(), List.of())),
                List.of());
    }
}
