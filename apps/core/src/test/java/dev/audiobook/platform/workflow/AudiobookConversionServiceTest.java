package dev.audiobook.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.narration.NarrationSelectionService;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AudiobookConversionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NarrationSelectionService narrationSelectionService = mock(NarrationSelectionService.class);
    private final PlatformIdentifierGenerator identifierGenerator = mock(PlatformIdentifierGenerator.class);
    private final ConversionWorkflowService conversionWorkflowService = mock(ConversionWorkflowService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneOffset.UTC);
    private final AudiobookConversionService service =
            new AudiobookConversionServiceImpl(
                    jdbcTemplate,
                    clock,
                    narrationSelectionService,
                    identifierGenerator,
                    conversionWorkflowService);
    private final UUID listenerId = UUID.randomUUID();
    private final UUID conversionId = UUID.randomUUID();
    private final NarrationSelectionService.GenerationAuthorization authorization =
            new NarrationSelectionService.GenerationAuthorization(UUID.randomUUID(), "a".repeat(64));

    @BeforeEach
    void authorizeRecipe() {
        given(narrationSelectionService.authorizeGeneration(listenerId, conversionId))
                .willReturn(authorization);
    }

    @Test
    void authorizesTheFrozenRecipeBeforeStartingSpeechGeneration() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(1);

        assertThat(service.beginSpeechGeneration(listenerId, conversionId)).isEqualTo(authorization);

        InOrder order = inOrder(narrationSelectionService, jdbcTemplate);
        order.verify(narrationSelectionService).authorizeGeneration(listenerId, conversionId);
        order.verify(jdbcTemplate).update(anyString(), eq(conversionId), eq(listenerId));
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId));
    }

    @Test
    void startingAnAlreadyGeneratingConversionIsIdempotent() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(0);
        given(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId)))
                .willReturn(AudiobookConversionService.ConversionState.GENERATING.name());

        assertThat(service.beginSpeechGeneration(listenerId, conversionId)).isEqualTo(authorization);
    }

    @Test
    void rejectsAConversionThatCannotTransitionToGenerating() {
        given(jdbcTemplate.update(anyString(), eq(conversionId), eq(listenerId))).willReturn(0);
        given(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq(conversionId), eq(listenerId)))
                .willReturn(AudiobookConversionService.ConversionState.PREPARING.name());

        assertThatThrownBy(() -> service.beginSpeechGeneration(listenerId, conversionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Audiobook Conversion cannot begin speech generation");
    }

    @Test
    void createsPreparingConversionWithTheRequestedReason() {
        UUID sourcePublicationId = UUID.randomUUID();

        service.createPreparing(
                conversionId,
                listenerId,
                sourcePublicationId,
                AudiobookConversionService.PreparationReason.EXTRACTION_PENDING);

        verify(jdbcTemplate).update(
                contains("INSERT INTO audiobook_conversion"),
                eq(conversionId),
                eq(listenerId),
                eq(sourcePublicationId),
                eq("EXTRACTION_PENDING"),
                eq(Timestamp.from(clock.instant())));
    }

    @Test
    void schedulesNarrationWorkAndItsOutboxMessageTogether() {
        UUID submissionId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        given(identifierGenerator.generate()).willReturn(workId, messageId);

        service.scheduleNarrationPlan(listenerId, conversionId, submissionId);

        verify(jdbcTemplate).update(
                contains("INSERT INTO workflow.narration_plan_work"),
                eq(workId),
                eq(listenerId),
                eq(conversionId),
                eq(submissionId),
                eq("narration-plan:" + conversionId),
                eq(Timestamp.from(clock.instant())));
        verify(jdbcTemplate).update(
                contains("INSERT INTO workflow.narration_plan_outbox"),
                eq(messageId),
                eq(workId),
                eq(Timestamp.from(clock.instant())));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void relaysNarrationWorkAndMarksOnlyThePublishedMessage() throws Exception {
        UUID workId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        BiConsumer<UUID, UUID> publisher = mock(BiConsumer.class);
        given(jdbcTemplate.query(anyString(), any(RowMapper.class))).willAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            given(resultSet.getObject("message_id", UUID.class)).willReturn(messageId);
            given(resultSet.getObject("work_id", UUID.class)).willReturn(workId);
            return List.of(mapper.mapRow(resultSet, 0));
        });
        given(jdbcTemplate.update(
                        contains("SET published_at"),
                        eq(Timestamp.from(clock.instant())),
                        eq(messageId)))
                .willReturn(1);

        assertThat(service.relayNarrationPlanWork(publisher)).isOne();

        verify(publisher).accept(messageId, workId);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void suppliesBoundedWorkflowOwnedRecoveryCandidates() throws Exception {
        given(jdbcTemplate.query(anyString(), any(RowMapper.class))).willAnswer(invocation -> {
            RowMapper mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            given(resultSet.getObject("conversion_id", UUID.class)).willReturn(conversionId);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        assertThat(service.narrationPlanRecoveryCandidates()).containsExactly(conversionId);

        verify(jdbcTemplate).query(contains("LIMIT 100"), any(RowMapper.class));
    }

    @Test
    void reconcilesConfirmedPlansThenAppliesCompletedExhaustedAndPausedResults() {
        given(jdbcTemplate.update(
                        contains("SET state = 'SUCCEEDED'"),
                        eq(Timestamp.from(clock.instant())),
                        eq(conversionId)))
                .willReturn(1);
        given(jdbcTemplate.update(anyString())).willReturn(2, 1, 1);

        assertThat(service.applyNarrationPlanResults(List.of(conversionId))).isEqualTo(4);

        verify(jdbcTemplate).update(
                contains("SET state = 'SUCCEEDED'"),
                eq(Timestamp.from(clock.instant())),
                eq(conversionId));
        verify(jdbcTemplate).update(contains("w.pause_reason_code = 'SOURCE_TOO_DAMAGED'"));
    }
}
