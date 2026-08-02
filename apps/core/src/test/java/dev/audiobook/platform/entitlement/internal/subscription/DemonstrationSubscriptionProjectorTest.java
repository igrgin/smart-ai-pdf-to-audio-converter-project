package dev.audiobook.platform.entitlement.internal.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DemonstrationSubscriptionProjectorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DemonstrationSubscriptionProjector projector = new DemonstrationSubscriptionProjectorImpl(
            jdbcTemplate,
            new DemonstrationSubscriptionProperties(
                    "whsec_test",
                    "price_demo_monthly",
                    500_000,
                    Duration.ofMinutes(5),
                    true),
            Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC));

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void emptyInboxIsAStableRetryBoundary() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(projector.projectPending()).isZero();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void malformedVerifiedEvidenceStaysPendingForRetry() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper mapper = invocation.getArgument(1);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("event_id")).thenReturn("evt_malformed");
                    when(resultSet.getString("event_type")).thenReturn("invoice.paid");
                    when(resultSet.getObject("event_created", OffsetDateTime.class))
                            .thenReturn(OffsetDateTime.parse("2026-08-01T11:59:00Z"));
                    when(resultSet.getString("payload")).thenReturn("{");
                    when(resultSet.getString("payload_sha256")).thenReturn("a".repeat(64));
                    return List.of(mapper.mapRow(resultSet, 0));
                });

        assertThatThrownBy(projector::projectPending)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be parsed");
        verify(jdbcTemplate, never())
                .update(contains("UPDATE stripe_demonstration_event_inbox"), any(Object[].class));
    }
}
