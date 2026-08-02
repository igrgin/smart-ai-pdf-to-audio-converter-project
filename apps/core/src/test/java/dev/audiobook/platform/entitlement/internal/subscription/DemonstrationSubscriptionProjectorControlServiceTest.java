package dev.audiobook.platform.entitlement.internal.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DemonstrationSubscriptionProjectorControlServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DemonstrationSubscriptionProjectorControlService controlService =
            new DemonstrationSubscriptionProjectorControlServiceImpl(
                    jdbcTemplate,
                    Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void readsAndChangesTheDurablePauseState() {
        when(jdbcTemplate.queryForObject(contains("SELECT paused"), org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(true);

        assertThat(controlService.isPaused()).isTrue();
        controlService.pause();
        controlService.resume();

        verify(jdbcTemplate, times(2)).update(contains("UPDATE demonstration_subscription_projector_control"), any(Object[].class));
    }
}
