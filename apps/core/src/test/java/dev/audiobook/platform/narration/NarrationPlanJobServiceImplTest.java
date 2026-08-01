package dev.audiobook.platform.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.admission.QuarantineObjectStore;
import dev.audiobook.platform.identifier.PlatformIdentifierGenerator;
import dev.audiobook.platform.workflow.ConversionLifecycleService;
import dev.audiobook.platform.workflow.ConversionWorkflowService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class NarrationPlanJobServiceImplTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final QuarantineObjectStore objectStore = mock(QuarantineObjectStore.class);
    private final NarrationPlanService narrationPlanService = mock(NarrationPlanService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneOffset.UTC);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ConversionWorkflowService workflowService = mock(ConversionWorkflowService.class);
    private final ConversionLifecycleService lifecycleService = mock(ConversionLifecycleService.class);
    private final PlatformIdentifierGenerator identifierGenerator = mock(PlatformIdentifierGenerator.class);
    private NarrationPlanJobService service;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new NarrationPlanJobServiceImpl(
                jdbcTemplate,
                objectStore,
                narrationPlanService,
                clock,
                transactionManager,
                workflowService,
                lifecycleService,
                identifierGenerator);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDurableQueueCompletesWithoutReadingPrivateAssets() {
        given(jdbcTemplate.query(
                        anyString(),
                        any(RowMapper.class),
                        eq(Timestamp.from(clock.instant())),
                        eq(4),
                        eq(20)))
                .willReturn(List.of());

        assertThat(service.processPending()).isZero();

        verifyNoInteractions(objectStore, narrationPlanService);
    }

    @Test
    void selectedPreparingWorkReadsItsSourceAndPreparesExactlyOnePlan() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        byte[] source = new byte[] {1, 2, 3};
        stubPending(messageId, workId, listenerId, conversionId, submissionId, true);
        given(objectStore.read(submissionId)).willReturn(new ByteArrayInputStream(source));

        assertThat(service.processPending()).isEqualTo(1);

        verify(narrationPlanService).prepare(eq(listenerId), eq(conversionId), any(ByteArrayInputStream.class));
        verify(jdbcTemplate).update(
                contains("SET state = 'SUCCEEDED'"),
                eq(Timestamp.from(clock.instant())),
                eq(workId),
                eq(messageId));
    }

    @Test
    void sourceReadFailureLeavesDurablePreparingWorkForARetry() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        stubPending(messageId, workId, listenerId, conversionId, submissionId, true);
        given(objectStore.read(submissionId)).willThrow(new IOException("unavailable"));

        assertThatThrownBy(service::processPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Narration Plan source Working Asset is unavailable");

        verifyNoInteractions(narrationPlanService);
        verify(jdbcTemplate).update(
                contains("CASE WHEN attempt_count"),
                eq(4),
                eq(workId),
                eq(messageId));
    }

    @Test
    void excessivePdfDamagePausesAtTheSafeResumeCheckpointWithoutRetrying() throws Exception {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        stubPending(messageId, workId, listenerId, conversionId, submissionId, true);
        given(objectStore.read(submissionId)).willReturn(new ByteArrayInputStream(new byte[] {1}));
        doThrow(new SourceTooDamagedException(17))
                .when(narrationPlanService)
                .prepare(eq(listenerId), eq(conversionId), any(ByteArrayInputStream.class));

        assertThat(service.processPending()).isZero();

        verify(jdbcTemplate).update(
                contains("SET state = 'PAUSED'"),
                eq("SOURCE_TOO_DAMAGED"),
                eq(17),
                eq(SourceTooDamagedException.LISTENER_GUIDANCE),
                eq(workId),
                eq(messageId));
    }

    @Test
    void duplicateDeliveryCannotClaimCompletedOrActivelyLeasedWork() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        stubPending(
                messageId, workId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), false);
                given(jdbcTemplate.update(
                        contains("SET state = 'CLAIMED'"),
                        any(UUID.class),
                        any(Timestamp.class),
                        eq(workId),
                        eq(4),
                        any(Timestamp.class)))
                .willReturn(0);

        assertThat(service.processDelivery(messageId, workId)).isFalse();

        verifyNoInteractions(objectStore, narrationPlanService);
        verify(jdbcTemplate).update(
                contains("narration_plan_inbox"),
                eq(messageId),
                eq(workId),
                eq(Timestamp.from(clock.instant())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPending(
            UUID messageId,
            UUID workId,
            UUID listenerId,
            UUID conversionId,
            UUID submissionId,
            boolean claimable)
            throws Exception {
        given(jdbcTemplate.query(
                        anyString(),
                        any(RowMapper.class),
                        eq(Timestamp.from(clock.instant())),
                        eq(4),
                        eq(20)))
                .willAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getObject("message_id", UUID.class)).willReturn(messageId);
                    given(resultSet.getObject("work_id", UUID.class)).willReturn(workId);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        given(jdbcTemplate.update(
                        contains("SET state = 'CLAIMED'"),
                        any(UUID.class),
                        any(Timestamp.class),
                        eq(workId),
                        eq(4),
                        any(Timestamp.class)))
                .willReturn(claimable ? 1 : 0);
        given(jdbcTemplate.queryForObject(
                        contains("SELECT w.listener_id"), any(RowMapper.class), eq(workId), eq(messageId)))
                .willAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getObject("listener_id", UUID.class)).willReturn(listenerId);
                    given(resultSet.getObject("conversion_id", UUID.class)).willReturn(conversionId);
                    given(resultSet.getObject("submission_id", UUID.class)).willReturn(submissionId);
                    given(resultSet.getInt("schema_version")).willReturn(1);
                    given(resultSet.getLong("expected_conversion_version")).willReturn(0L);
                    return mapper.mapRow(resultSet, 0);
                });
        given(workflowService.claimDelivery(any()))
                .willReturn(new ConversionWorkflowService.DeliveryDecision(
                        ConversionWorkflowService.DeliveryDisposition.CLAIMED,
                        UUID.randomUUID(),
                        null));
        given(workflowService.acceptResult(any()))
                .willReturn(new ConversionWorkflowService.ResultDecision(
                        ConversionWorkflowService.ResultDisposition.ACCEPTED,
                        UUID.randomUUID(),
                        null));
        given(jdbcTemplate.queryForObject(
                        contains("SELECT working_asset_ref"),
                        any(RowMapper.class),
                        eq(listenerId),
                        eq(conversionId)))
                .willAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getString("working_asset_ref"))
                            .willReturn("working/narration/plan.json");
                    given(resultSet.getString("asset_sha256")).willReturn("a".repeat(64));
                    return mapper.mapRow(resultSet, 0);
                });
    }
}
