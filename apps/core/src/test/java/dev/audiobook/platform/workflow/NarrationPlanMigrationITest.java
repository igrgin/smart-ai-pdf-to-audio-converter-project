package dev.audiobook.platform.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("itest")
class NarrationPlanMigrationITest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgres:17.6-alpine"))
            .withDatabaseName("audiobook")
            .withUsername("audiobook")
            .withPassword("audiobook-test");

    @Test
    void migrationBackfillsPreparingEpubAndPdfConversions() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();

        seedPreparingConversion("a", "application/epub+zip");
        seedPreparingConversion("b", "application/pdf");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(reasonCode("a")).isEqualTo("NARRATION_PLAN_PENDING");
        assertThat(reasonCode("b")).isEqualTo("EXTRACTION_PENDING");
        assertThat(workCount("a")).isOne();
        assertThat(workCount("b")).isOne();
        assertThat(outboxCount("a")).isOne();
        assertThat(outboxCount("b")).isOne();
    }

    private static void seedPreparingConversion(String suffix, String mediaType) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO listener_identity (listener_id, display_name)
                    VALUES ('01985f42-5f8d-7000-8000-00000000000%s', 'Listener %s');

                    INSERT INTO admission.rights_attestation (
                        attestation_id, listener_id, terms_version, notice_version, submitted_at
                    ) VALUES (
                        '01985f42-5f8d-7000-8100-00000000000%s',
                        '01985f42-5f8d-7000-8000-00000000000%s',
                        'terms-v1', 'notice-v1', CURRENT_TIMESTAMP
                    );

                    INSERT INTO admission.publication_submission (
                        submission_id, listener_id, attestation_id, entitlement_reservation_id,
                        planned_conversion_id, state, declared_media_type, declared_byte_length,
                        declared_sha256, upload_expires_at, created_at, updated_at
                    ) VALUES (
                        '01985f42-5f8d-7000-8200-00000000000%s',
                        '01985f42-5f8d-7000-8000-00000000000%s',
                        '01985f42-5f8d-7000-8100-00000000000%s',
                        '01985f42-5f8d-7000-8300-00000000000%s',
                        '01985f42-5f8d-7000-8500-00000000000%s',
                        'ADMITTED', '%s', 100, repeat('a', 64),
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    );

                    INSERT INTO admission.source_publication (
                        source_publication_id, listener_id, submission_id, media_type, byte_length, created_at
                    ) VALUES (
                        '01985f42-5f8d-7000-8400-00000000000%s',
                        '01985f42-5f8d-7000-8000-00000000000%s',
                        '01985f42-5f8d-7000-8200-00000000000%s',
                        '%s', 100, CURRENT_TIMESTAMP
                    );

                    INSERT INTO workflow.audiobook_conversion (
                        conversion_id, listener_id, source_publication_id, state, created_at
                    ) VALUES (
                        '01985f42-5f8d-7000-8500-00000000000%s',
                        '01985f42-5f8d-7000-8000-00000000000%s',
                        '01985f42-5f8d-7000-8400-00000000000%s',
                        'PREPARING', CURRENT_TIMESTAMP
                    );
                    """.formatted(
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    suffix,
                    mediaType,
                    suffix,
                    suffix,
                    suffix,
                    mediaType,
                    suffix,
                    suffix,
                    suffix));
        }
    }

    private static String reasonCode(String suffix) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT reason_code
                        FROM workflow.audiobook_conversion
                        WHERE conversion_id = '01985f42-5f8d-7000-8500-00000000000%s'
                        """.formatted(suffix))) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static int workCount(String suffix) throws SQLException {
        return count("""
                SELECT count(*)
                FROM workflow.narration_plan_work
                WHERE conversion_id = '01985f42-5f8d-7000-8500-00000000000%s'
                """.formatted(suffix));
    }

    private static int outboxCount(String suffix) throws SQLException {
        return count("""
                SELECT count(*)
                FROM workflow.narration_plan_outbox outbox
                JOIN workflow.narration_plan_work work ON work.work_id = outbox.work_id
                WHERE work.conversion_id = '01985f42-5f8d-7000-8500-00000000000%s'
                """.formatted(suffix));
    }

    private static int count(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
