package dev.audiobook.platform.bootstrap.composition.entitlement;

import dev.audiobook.platform.entitlement.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.internal.oidc.ExternalIdentity;
import dev.audiobook.platform.identity.internal.session.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class ConversionEntitlementTransactionITest {

    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    ConversionEntitlementTransactionITest(
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService,
            JdbcTemplate jdbcTemplate) {
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void providerLedgerFailureRollsBackTheWholeAdmission() {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "transaction-rollback-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Rollback Listener"))
                .listenerId();
        entitlementService.approveFreeGrant(
                listenerId, "rollback-approval-" + listenerId, "rollback-grant-" + listenerId);
        UUID conversionId = UUID.randomUUID();
        installProviderFailureTrigger();
        try {
            assertThatThrownBy(() -> entitlementService.authorizeSpeech(
                            new ConversionEntitlementService.AdmissionRequest(
                                    listenerId,
                                    conversionId,
                                    "rollback-provider",
                                    "recipe-free-v1",
                                    "rates-2026-08",
                                    10_000,
                                    100_000,
                                    "rollback-admission-" + conversionId)))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("simulated provider ledger failure");

            assertThat(count("character_entitlement_ledger_entry", conversionId)).isZero();
            assertThat(count("provider_spend_ledger_entry", conversionId)).isZero();
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM entitlement_operation WHERE operation_key = ?",
                            Long.class,
                            "rollback-admission-" + conversionId))
                    .isZero();
            assertThat(count("entitlement_audit_event", conversionId)).isZero();
        } finally {
            jdbcTemplate.execute(
                    "DROP TRIGGER entitlement_test_provider_failure ON provider_spend_ledger_entry");
            jdbcTemplate.execute("DROP FUNCTION reject_test_provider_reservation()");
        }
    }

    private void installProviderFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_test_provider_reservation() RETURNS trigger AS $$
                BEGIN
                    IF NEW.provider = 'rollback-provider' THEN
                        RAISE EXCEPTION 'simulated provider ledger failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER entitlement_test_provider_failure
                BEFORE INSERT ON provider_spend_ledger_entry
                FOR EACH ROW EXECUTE FUNCTION reject_test_provider_reservation()
                """);
    }

    private long count(String table, UUID conversionId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE conversion_id = ?",
                Long.class,
                conversionId);
        return count == null ? 0 : count;
    }
}
