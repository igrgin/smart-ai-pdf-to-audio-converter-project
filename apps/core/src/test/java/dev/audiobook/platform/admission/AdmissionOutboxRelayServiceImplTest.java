package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.audiobook.platform.narration.NarrationPlanWorkPublisher;
import dev.audiobook.platform.workflow.AudiobookConversionService;
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

class AdmissionOutboxRelayServiceImplTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final InspectionWorkPublisher inspectionPublisher = mock(InspectionWorkPublisher.class);
    private final NarrationPlanWorkPublisher narrationPublisher = mock(NarrationPlanWorkPublisher.class);
    private final AudiobookConversionService audiobookConversionService = mock(AudiobookConversionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), ZoneOffset.UTC);
    private AdmissionOutboxRelayService service;

    @BeforeEach
    void setUp() {
        service = new AdmissionOutboxRelayServiceImpl(
                jdbcTemplate,
                inspectionPublisher,
                List.of(narrationPublisher),
                audiobookConversionService,
                clock);
    }

    @Test
    void independentlyRelaysAndMarksInspectionAndNarrationMessages() throws Exception {
        UUID inspectionMessage = UUID.randomUUID();
        UUID inspectionWork = UUID.randomUUID();
        UUID narrationMessage = UUID.randomUUID();
        UUID narrationWork = UUID.randomUUID();
        stubSelections(inspectionMessage, inspectionWork, narrationMessage, narrationWork);
        given(jdbcTemplate.update(anyString(), any(Timestamp.class), any(UUID.class))).willReturn(1);
        relayNarration(narrationMessage, narrationWork);

        assertThat(service.relayPending()).isEqualTo(2);

        verify(inspectionPublisher).publish(inspectionMessage, inspectionWork);
        verify(narrationPublisher).publish(narrationMessage, narrationWork);
        verify(audiobookConversionService).relayNarrationPlanWork(any());
    }

    @Test
    void publisherFailureLeavesTheNarrationOutboxMessagePending() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        stubSelections(null, null, messageId, workId);
        given(audiobookConversionService.relayNarrationPlanWork(any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.BiConsumer<UUID, UUID> publisher = invocation.getArgument(0);
            publisher.accept(messageId, workId);
            return 1;
        });
        org.mockito.Mockito.doThrow(new IllegalStateException("queue unavailable"))
                .when(narrationPublisher).publish(messageId, workId);

        assertThatThrownBy(service::relayPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("queue unavailable");

        verify(audiobookConversionService).relayNarrationPlanWork(any());
    }

    @Test
    void localCompositionLeavesNarrationMessagesForThePollingWorker() throws Exception {
        UUID narrationMessage = UUID.randomUUID();
        UUID narrationWork = UUID.randomUUID();
        service = new AdmissionOutboxRelayServiceImpl(
                jdbcTemplate, inspectionPublisher, List.of(), audiobookConversionService, clock);
        stubSelections(null, null, narrationMessage, narrationWork);

        assertThat(service.relayPending()).isZero();

        verify(audiobookConversionService, never()).relayNarrationPlanWork(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubSelections(
            UUID inspectionMessage,
            UUID inspectionWork,
            UUID narrationMessage,
            UUID narrationWork)
            throws Exception {
        given(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(20)))
                .willAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    boolean narration = sql.contains("narration_plan_outbox");
                    UUID messageId = narration ? narrationMessage : inspectionMessage;
                    if (messageId == null) {
                        return List.of();
                    }
                    UUID workId = narration ? narrationWork : inspectionWork;
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    given(resultSet.getObject("message_id", UUID.class)).willReturn(messageId);
                    given(resultSet.getObject("work_id", UUID.class)).willReturn(workId);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    private void relayNarration(UUID messageId, UUID workId) {
        given(audiobookConversionService.relayNarrationPlanWork(any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.BiConsumer<UUID, UUID> publisher = invocation.getArgument(0);
            publisher.accept(messageId, workId);
            return 1;
        });
    }
}
