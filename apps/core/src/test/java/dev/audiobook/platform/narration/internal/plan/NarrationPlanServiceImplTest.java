package dev.audiobook.platform.narration.internal.plan;

import dev.audiobook.platform.narration.NarrationPlanAssetStore;
import dev.audiobook.platform.narration.NarrationPlanConversionAccess;
import dev.audiobook.platform.narration.NarrationPlanService;
import dev.audiobook.platform.narration.internal.assets.NarrationPlanAssetIdentity;
import dev.audiobook.platform.narration.internal.document.AdmittedPublicationNarrationPlanInterpreter;
import dev.audiobook.platform.narration.internal.document.EpubNarrationPlanInterpreter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class NarrationPlanServiceImplTest {

    private final AdmittedPublicationNarrationPlanInterpreter interpreter =
            mock(AdmittedPublicationNarrationPlanInterpreter.class);
    private final NarrationPlanAssetStore assetStore = mock(NarrationPlanAssetStore.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PlatformIdentifierGenerator identifierGenerator = mock(PlatformIdentifierGenerator.class);
    private final NarrationPlanConversionAccess conversionAccess = mock(NarrationPlanConversionAccess.class);
    private final Clock clock = mock(Clock.class);
    private NarrationPlanService service;

    @BeforeEach
    void setUp() {
        service = new NarrationPlanServiceImpl(
                interpreter, assetStore, jdbcTemplate, identifierGenerator, conversionAccess, clock);
    }

    @Test
    void completedConversionReplayDoesNotReadOrRewritePrivateAssets() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(conversionAccess.awaitingReview(listenerId, conversionId)).willReturn(true);

        service.prepare(listenerId, conversionId, new ByteArrayInputStream(new byte[] {1}));

        verifyNoInteractions(interpreter, assetStore, jdbcTemplate, identifierGenerator, clock);
    }

    @Test
    void relationalPlanReplayDoesNotReadOrRewritePrivateAssets() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(listenerId), eq(conversionId)))
                .willReturn(1);

        service.prepare(listenerId, conversionId, new ByteArrayInputStream(new byte[] {1}));

        verifyNoInteractions(interpreter, assetStore, identifierGenerator, clock);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void confirmsOnlyCandidateConversionsWithNarrationOwnedPlans() throws Exception {
        UUID planned = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(planned), eq(missing)))
                .willAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getObject("conversion_id", UUID.class)).willReturn(planned);
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertThat(service.existingPlanConversionIds(List.of(planned, missing))).containsExactly(planned);
        assertThat(service.existingPlanConversionIds(List.of())).isEmpty();

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), eq(planned), eq(missing));
    }

    @Test
    void workingAssetFailureLeavesThePlanUncommittedForRetry() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(listenerId), eq(conversionId)))
                .willReturn(0);
        given(interpreter.interpret(any())).willReturn(plan());
        given(assetStore.write(eq(conversionId), any(byte[].class)))
                .willThrow(new IOException("unavailable"));

        assertThatThrownBy(() -> service.prepare(
                        listenerId, conversionId, new ByteArrayInputStream(new byte[] {1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan Working Asset storage is unavailable");

        verifyNoInteractions(identifierGenerator, clock);
    }

    @Test
    void missingOrWrongSchemaPlanIsNotExposed() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(listenerId), eq(conversionId)))
                .willReturn(List.of());

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubStoredPlan(
            UUID listenerId,
            UUID conversionId,
            String reference,
            String digest,
            String schemaVersion)
            throws Exception {
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(listenerId), eq(conversionId)))
                .willAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getString("working_asset_ref")).willReturn(reference);
                    given(resultSet.getString("asset_sha256")).willReturn(digest);
                    given(resultSet.getString("schema_version")).willReturn(schemaVersion);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    private static EpubNarrationPlanInterpreter.NarrationPlan plan() {
        var provenance = new EpubNarrationPlanInterpreter.StructuralProvenance(
                EpubNarrationPlanInterpreter.ProvenanceSource.EPUB_SPINE,
                0,
                "OPS/chapter.xhtml",
                null,
                true,
                new EpubNarrationPlanInterpreter.Confidence(1.0));
        return new EpubNarrationPlanInterpreter.NarrationPlan(
                List.of(new EpubNarrationPlanInterpreter.Chapter(
                        0, "Chapter", provenance, List.of(), List.of())),
                List.of());
    }
}
