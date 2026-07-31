package dev.audiobook.platform.status;

import static dev.audiobook.platform.status.PlatformStatus.Availability.AVAILABLE;
import static dev.audiobook.platform.status.PlatformStatus.Availability.DEGRADED;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlatformStatusServiceImpl implements PlatformStatusService {

    private final JdbcTemplate jdbcTemplate;
    private final PlatformBuildProperties buildProperties;

    @Override
    public PlatformStatus currentStatus() {
        return new PlatformStatus(
                "v1",
                buildProperties.version(),
                buildProperties.revision(),
                AVAILABLE,
                databaseAvailability());
    }

    private PlatformStatus.Availability databaseAvailability() {
        try {
            Boolean migrationReady = jdbcTemplate.queryForObject(
                    "select exists(select 1 from platform_status where singleton)",
                    Boolean.class);
            return Boolean.TRUE.equals(migrationReady) ? AVAILABLE : DEGRADED;
        } catch (DataAccessException exception) {
            return DEGRADED;
        }
    }
}
