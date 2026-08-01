package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("itest")
class InspectionDatabaseBoundaryITest {

    private static final String INSPECTION_USER = "audiobook_inspection";
    private static final String INSPECTION_PASSWORD = "inspection-test";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.6-alpine"))
            .withDatabaseName("audiobook")
            .withUsername("audiobook")
            .withPassword("audiobook-test");

    @BeforeAll
    static void migrateWithInspectionRolePresent() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + INSPECTION_USER + " LOGIN PASSWORD '" + INSPECTION_PASSWORD + "'");
        }
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void inspectionLoginHasOnlyItsNarrowTablesAndCannotReadOrMutateSubmissionState() throws Exception {
        assertThat(tablePrivileges()).isEmpty();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), INSPECTION_USER, INSPECTION_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("SET search_path TO admission, workflow, public");
            assertThat(statement.executeQuery(
                            "SELECT count(*) FROM workflow.pending_inspections(CURRENT_TIMESTAMP, 10)")
                    .next()).isTrue();
            assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM publication_submission"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM rights_attestation"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE publication_submission SET state = 'ADMITTED' WHERE false"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void oneClaimCapabilityCannotReadOrRecordAnotherWorkersPublication() throws Exception {
        String listenerA = "01985f42-5f8d-7000-8000-0000000000a1";
        String listenerB = "01985f42-5f8d-7000-8000-0000000000b1";
        String submissionA = "01985f42-5f8d-7000-8000-0000000000a2";
        String submissionB = "01985f42-5f8d-7000-8000-0000000000b2";
        String workA = "01985f42-5f8d-7000-8000-0000000000a3";
        String workB = "01985f42-5f8d-7000-8000-0000000000b3";
        seedWork(listenerA, submissionA, workA, "a");
        seedWork(listenerB, submissionB, workB, "b");

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), INSPECTION_USER, INSPECTION_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeQuery("""
                    SELECT * FROM workflow.claim_inspection(
                        '%s', 'worker-a', CURRENT_TIMESTAMP + INTERVAL '1 minute', 'inspect-%s', 3
                    )
                    """.formatted(workA, workA));
            statement.executeQuery("""
                    SELECT * FROM workflow.claim_inspection(
                        '%s', 'worker-b', CURRENT_TIMESTAMP + INTERVAL '1 minute', 'inspect-%s', 3
                    )
                    """.formatted(workB, workB));

            try (var own = statement.executeQuery(
                    "SELECT submission_id FROM admission.inspection_subject('%s', 'worker-a')".formatted(workA))) {
                assertThat(own.next()).isTrue();
                assertThat(own.getString(1)).isEqualTo(submissionA);
            }
            try (var other = statement.executeQuery(
                    "SELECT submission_id FROM admission.inspection_subject('%s', 'worker-a')".formatted(workB))) {
                assertThat(other.next()).isFalse();
            }
            assertThatThrownBy(() -> statement.executeQuery("""
                            SELECT admission.record_inspection_result(
                                '01985f42-5f8d-7000-8000-0000000000ff', '%s', 'worker-a', 'inspect-%s',
                                'ADMITTED', NULL, 'application/pdf', 'test-toolchain', CURRENT_TIMESTAMP
                            )
                            """.formatted(workB, workB)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void resultRowsAreConstrainedToTheSameDurableWorkCoordinates() throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var constraints = statement.executeQuery(
                        """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE constraint_schema = 'admission'
                          AND table_name = 'inspection_result'
                          AND constraint_type = 'FOREIGN KEY'
                        """)) {
            List<String> names = new ArrayList<>();
            while (constraints.next()) {
                names.add(constraints.getString(1));
            }
            assertThat(names).contains("inspection_result_matches_work");
        }
    }

    @Test
    void inspectionLoginCannotExpandLeaseOrRetryPolicy() throws Exception {
        String listener = "01985f42-5f8d-7000-8000-0000000000c1";
        String submission = "01985f42-5f8d-7000-8000-0000000000c2";
        String work = "01985f42-5f8d-7000-8000-0000000000c3";
        seedWork(listener, submission, work, "c");

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), INSPECTION_USER, INSPECTION_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeQuery("""
                    SELECT * FROM workflow.claim_inspection(
                        '%s', 'worker-c', CURRENT_TIMESTAMP + INTERVAL '10 minutes', 'inspect-%s', 3
                    )
                    """.formatted(work, work));
            assertThat(leaseIsWithinDatabaseRuntime(work)).isTrue();
            assertThatThrownBy(() -> statement.executeQuery("""
                            SELECT * FROM workflow.claim_inspection(
                                '%s', 'worker-c', CURRENT_TIMESTAMP + INTERVAL '1 minute', 'inspect-%s', 4
                            )
                            """.formatted(work, work)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void expiredLeaseCannotReadOrRecordAnInspectionResult() throws Exception {
        String listener = "01985f42-5f8d-7000-8000-0000000000d1";
        String submission = "01985f42-5f8d-7000-8000-0000000000d2";
        String work = "01985f42-5f8d-7000-8000-0000000000d3";
        seedWork(listener, submission, work, "d");

        try (var inspectionConnection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), INSPECTION_USER, INSPECTION_PASSWORD);
                var inspectionStatement = inspectionConnection.createStatement()) {
            inspectionStatement.executeQuery("""
                    SELECT * FROM workflow.claim_inspection(
                        '%s', 'worker-d', CURRENT_TIMESTAMP + INTERVAL '1 minute', 'inspect-%s', 3
                    )
                    """.formatted(work, work));

            expireLease(work);

            try (var subject = inspectionStatement.executeQuery(
                    "SELECT submission_id FROM admission.inspection_subject('%s', 'worker-d')".formatted(work))) {
                assertThat(subject.next()).isFalse();
            }
            assertThatThrownBy(() -> inspectionStatement.executeQuery("""
                            SELECT admission.record_inspection_result(
                                '01985f42-5f8d-7000-8000-0000000000df', '%s', 'worker-d', 'inspect-%s',
                                'ADMITTED', NULL, 'application/pdf', 'test-toolchain', CURRENT_TIMESTAMP
                            )
                            """.formatted(work, work)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void inspectionLoginCannotCompleteWorkWithoutRecordingAResult() throws Exception {
        String listener = "01985f42-5f8d-7000-8000-0000000000e1";
        String submission = "01985f42-5f8d-7000-8000-0000000000e2";
        String work = "01985f42-5f8d-7000-8000-0000000000e3";
        seedWork(listener, submission, work, "e");

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), INSPECTION_USER, INSPECTION_PASSWORD);
                var statement = connection.createStatement()) {
            statement.executeQuery("""
                    SELECT * FROM workflow.claim_inspection(
                        '%s', 'worker-e', CURRENT_TIMESTAMP + INTERVAL '1 minute', 'inspect-%s', 3
                    )
                    """.formatted(work, work));
            assertThatThrownBy(() -> statement.executeQuery("""
                            SELECT workflow.complete_inspection(
                                '%s', 'worker-e', CURRENT_TIMESTAMP
                            )
                            """.formatted(work)))
                    .isInstanceOf(SQLException.class);
        }
    }

    private static List<String> tablePrivileges() throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        """
                        SELECT table_schema, table_name, privilege_type
                        FROM information_schema.role_table_grants
                        WHERE grantee = ?
                        ORDER BY table_schema, table_name, privilege_type
                        """)) {
            statement.setString(1, INSPECTION_USER);
            try (var results = statement.executeQuery()) {
                List<String> privileges = new ArrayList<>();
                while (results.next()) {
                    privileges.add(results.getString(1) + "." + results.getString(2) + ":" + results.getString(3));
                }
                return privileges;
            }
        }
    }

    private static void seedWork(String listenerId, String submissionId, String workId, String suffix)
            throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO listener_identity (listener_id, display_name) VALUES ('%1$s', 'Listener');
                    INSERT INTO admission.rights_attestation (
                        attestation_id, listener_id, terms_version, notice_version, submitted_at
                    ) VALUES ('01985f42-5f8d-7000-8000-0000000000%4$s4', '%1$s', 'rights-v1', 'notice-v1', CURRENT_TIMESTAMP);
                    INSERT INTO admission.publication_submission (
                        submission_id, listener_id, attestation_id, entitlement_reservation_id,
                        planned_conversion_id, state, declared_media_type, declared_byte_length,
                        declared_sha256, upload_expires_at, created_at, updated_at
                    ) VALUES (
                        '%2$s', '%1$s', '01985f42-5f8d-7000-8000-0000000000%4$s4',
                        '01985f42-5f8d-7000-8000-0000000000%4$s5',
                        '01985f42-5f8d-7000-8000-0000000000%4$s6', 'UPLOADED', 'application/pdf', 10,
                        '%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s%4$s',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    );
                    INSERT INTO workflow.inspection_work (
                        work_id, listener_id, submission_id, operation_key, state, created_at
                    ) VALUES ('%3$s', '%1$s', '%2$s', 'inspect-%3$s', 'PENDING', CURRENT_TIMESTAMP);
                    INSERT INTO workflow.inspection_inbox (message_id, work_id, accepted_at)
                    VALUES ('01985f42-5f8d-7000-8000-0000000000%4$s7', '%3$s', CURRENT_TIMESTAMP);
                    """.formatted(listenerId, submissionId, workId, suffix));
        }
    }

    private static void expireLease(String workId) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE workflow.inspection_work
                    SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE work_id = '%s'
                    """.formatted(workId));
        }
    }

    private static boolean leaseIsWithinDatabaseRuntime(String workId) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        SELECT lease_expires_at > CURRENT_TIMESTAMP
                           AND lease_expires_at <= CURRENT_TIMESTAMP + INTERVAL '9 minutes'
                        FROM workflow.inspection_work
                        WHERE work_id = '%s'
                        """.formatted(workId))) {
            return result.next() && result.getBoolean(1);
        }
    }
}
