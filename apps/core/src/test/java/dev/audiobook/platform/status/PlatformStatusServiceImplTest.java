package dev.audiobook.platform.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PlatformStatusServiceImplTest {

    @Test
    void databaseFailureIsReportedWithoutFailingThePublicStatus() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        given(jdbcTemplate.queryForObject(
                        "select exists(select 1 from platform_status where singleton)",
                        Boolean.class))
                .willThrow(new DataAccessResourceFailureException("connection details must stay private"));
        PlatformStatusService service = new PlatformStatusServiceImpl(
                jdbcTemplate,
                new PlatformBuildProperties("0.1.0", "a1b2c3d"));

        PlatformStatus status = service.currentStatus();

        assertThat(status.availability().core()).isEqualTo(PlatformStatus.Availability.AVAILABLE);
        assertThat(status.availability().database()).isEqualTo(PlatformStatus.Availability.DEGRADED);
        assertThat(status.toString()).doesNotContain("connection details");
    }

    @Test
    void missingMigrationSentinelIsReportedAsDegraded() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        given(jdbcTemplate.queryForObject(
                        "select exists(select 1 from platform_status where singleton)",
                        Boolean.class))
                .willReturn(false);
        PlatformStatusService service = new PlatformStatusServiceImpl(
                jdbcTemplate,
                new PlatformBuildProperties("0.1.0", "a1b2c3d"));

        assertThat(service.currentStatus().availability().database())
                .isEqualTo(PlatformStatus.Availability.DEGRADED);
    }
}
