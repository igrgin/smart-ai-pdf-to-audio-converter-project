package dev.audiobook.platform.entitlement;

import dev.audiobook.platform.entitlement.internal.ConversionEntitlementServiceImpl;
import dev.audiobook.platform.entitlement.internal.EntitlementPolicyProperties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ConversionEntitlementServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ConversionEntitlementService service = new ConversionEntitlementServiceImpl(
            jdbcTemplate,
            new EntitlementPolicyProperties(
                    500_000,
                    Duration.ofDays(365),
                    500_000,
                    5_000_000,
                    10_000_000,
                    100_000_000,
                    150_000_000,
                    1,
                    3),
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void rejectsInvalidGrantReferencesBeforePersistence() {
        UUID listenerId = UUID.randomUUID();

        assertThatThrownBy(() -> service.approveFreeGrant(listenerId, " ", "grant-operation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalReference");
        assertThatThrownBy(() -> service.approveFreeGrant(listenerId, "x".repeat(201), "grant-operation"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approvalReference");
        assertThatThrownBy(() -> service.approveFreeGrant(listenerId, "approval", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsInvalidAccountingAmountsBeforePersistence() {
        UUID listenerId = UUID.randomUUID();
        UUID conversionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.authorizeSpeech(new ConversionEntitlementService.AdmissionRequest(
                        listenerId,
                        conversionId,
                        "openai",
                        "recipe",
                        "rate-card",
                        0,
                        1,
                        "admission")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.authorizeSpeech(new ConversionEntitlementService.AdmissionRequest(
                        listenerId,
                        conversionId,
                        "openai",
                        "recipe",
                        "rate-card",
                        1,
                        -1,
                        "admission")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.settle(new ConversionEntitlementService.SettlementRequest(
                        UUID.randomUUID(), -1, 0, "settlement")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> service.correctCharacters(new ConversionEntitlementService.CorrectionRequest(
                        listenerId, 0, "evidence", "correction")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
        verifyNoInteractions(jdbcTemplate);
    }
}
