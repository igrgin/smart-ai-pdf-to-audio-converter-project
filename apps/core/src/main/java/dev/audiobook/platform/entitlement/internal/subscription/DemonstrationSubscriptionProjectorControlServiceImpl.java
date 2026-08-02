package dev.audiobook.platform.entitlement.internal.subscription;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DemonstrationSubscriptionProjectorControlServiceImpl
        implements DemonstrationSubscriptionProjectorControlService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public boolean isPaused() {
        Boolean paused = jdbcTemplate.queryForObject(
                "SELECT paused FROM demonstration_subscription_projector_control WHERE control_id = 1",
                Boolean.class);
        return Boolean.TRUE.equals(paused);
    }

    @Override
    @Transactional
    public void pause() {
        setPaused(true);
    }

    @Override
    @Transactional
    public void resume() {
        setPaused(false);
    }

    private void setPaused(boolean paused) {
        jdbcTemplate.update(
                """
                UPDATE demonstration_subscription_projector_control
                SET paused = ?, changed_at = ?
                WHERE control_id = 1
                """,
                paused,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
