package dev.audiobook.platform.entitlement.subscription.stripe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.entitlement.subscription.stripe.StripeWebhookVerificationException;
import dev.audiobook.platform.entitlement.subscription.stripe.VerifiedStripeEvent;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

class StripeEventInboxServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final StripeEventInboxService inboxService =
            new StripeEventInboxServiceImpl(
                    jdbcTemplate,
                    Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void exactDuplicateIsIdempotentButReusedEventIdWithDifferentEvidenceIsRejected() {
        VerifiedStripeEvent event =
                new VerifiedStripeEvent(
                        "evt_duplicate",
                        "invoice.paid",
                        Instant.parse("2026-08-01T11:59:00Z"),
                        "{}",
                        "a".repeat(64),
                        null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("evt_duplicate")))
                .thenReturn("a".repeat(64), "b".repeat(64));

        assertThat(inboxService.accept(event).received()).isFalse();
        assertThatThrownBy(() -> inboxService.accept(event))
                .isInstanceOf(StripeWebhookVerificationException.class)
                .hasMessageContaining("reused");
    }
}
