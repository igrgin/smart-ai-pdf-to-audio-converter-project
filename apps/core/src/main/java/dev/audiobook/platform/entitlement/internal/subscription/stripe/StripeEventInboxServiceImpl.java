package dev.audiobook.platform.entitlement.internal.subscription.stripe;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StripeEventInboxServiceImpl implements StripeEventInboxService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Override
    @Transactional
    public Receipt accept(VerifiedStripeEvent event) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO stripe_demonstration_event_inbox (
                    event_id, event_type, event_created, payload, payload_sha256,
                    projection_status, received_at
                ) VALUES (?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(),
                event.eventType(),
                event.eventCreated().atOffset(ZoneOffset.UTC),
                event.payload(),
                event.payloadSha256(),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        if (inserted == 0) {
            String storedHash = jdbcTemplate.queryForObject(
                    "SELECT payload_sha256 FROM stripe_demonstration_event_inbox WHERE event_id = ?",
                    String.class,
                    event.eventId());
            if (!event.payloadSha256().equals(storedHash)) {
                throw new StripeWebhookVerificationException("Stripe event ID was reused with another payload");
            }
        }
        return new Receipt(event.eventId(), inserted == 1);
    }
}
