package dev.audiobook.platform.entitlement.internal.subscription;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DemonstrationSubscriptionProjectorImpl implements DemonstrationSubscriptionProjector {

    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final DemonstrationSubscriptionProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional
    public int projectPending() {
        var events = jdbcTemplate.query(
                """
                SELECT event_id, event_type, event_created, payload::text, payload_sha256
                FROM stripe_demonstration_event_inbox
                WHERE projection_status = 'PENDING'
                ORDER BY event_created, event_id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new PendingEvent(
                        resultSet.getString("event_id"),
                        resultSet.getString("event_type"),
                        resultSet.getObject("event_created", OffsetDateTime.class).toInstant(),
                        resultSet.getString("payload"),
                        resultSet.getString("payload_sha256")),
                BATCH_SIZE);
        for (PendingEvent event : events) {
            ProjectionOutcome outcome = switch (event.eventType()) {
                case "invoice.paid" -> outcome(projectPaidInvoice(event));
                case "customer.subscription.updated", "customer.subscription.deleted" ->
                    outcome(projectSubscriptionState(event));
                case "refund.created", "refund.updated" -> projectRefund(event);
                case "invoice.voided", "invoice.marked_uncollectible" -> projectInvoiceCorrection(event);
                default -> ProjectionOutcome.IGNORED;
            };
            if (outcome != ProjectionOutcome.DEFERRED) {
                completeEvent(event.eventId(), outcome);
            }
        }
        return events.size();
    }

    private boolean projectPaidInvoice(PendingEvent event) {
        JsonNode invoice = eventObject(event);
        if (!invoice.path("paid").asBoolean(false) || !"paid".equals(text(invoice, "status"))) {
            return false;
        }
        String invoiceId = requiredText(invoice, "id");
        if (exists("demonstration_subscription_invoice_grant", "stripe_invoice_id", invoiceId)) {
            return true;
        }

        BillingPeriod period = billingPeriod(invoice);
        if (period == null) {
            return false;
        }

        JsonNode subscriptionDetails = invoice.path("parent").path("subscription_details");
        String subscriptionId = firstText(subscriptionDetails.path("subscription"), invoice.path("subscription"));
        String listenerReference = firstText(
                subscriptionDetails.path("metadata").path("listener_id"),
                invoice.path("subscription_details").path("metadata").path("listener_id"),
                invoice.path("metadata").path("listener_id"));
        UUID listenerId;
        try {
            listenerId = UUID.fromString(listenerReference);
        } catch (RuntimeException invalidListener) {
            throw new IllegalArgumentException("Paid invoice has no valid Listener mapping", invalidListener);
        }
        String customerId = requiredText(invoice, "customer");
        Instant now = clock.instant();
        upsertSubscription(new ProjectedSubscription(
                subscriptionId,
                listenerId,
                customerId,
                DemonstrationSubscriptionStatus.ACTIVE,
                period.start(),
                period.end(),
                event.eventCreated(),
                event.eventId(),
                now));

        lockEntitlementState();
        if (exists("demonstration_subscription_invoice_grant", "stripe_invoice_id", invoiceId)) {
            return true;
        }
        UUID grantId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO conversion_entitlement_grant (
                    grant_id, listener_id, grant_kind, evidence_reference,
                    granted_characters, valid_from, valid_until, created_at
                ) VALUES (?, ?, 'DEMONSTRATION_SUBSCRIPTION', ?, ?, ?, ?, ?)
                """,
                grantId,
                listenerId,
                "stripe-invoice:" + invoiceId,
                properties.monthlyGrantCharacters(),
                databaseTime(period.start()),
                databaseTime(period.end()),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO demonstration_subscription_invoice_grant (
                    stripe_invoice_id, stripe_subscription_id, grant_id,
                    stripe_payment_intent_id, stripe_charge_id, stripe_event_id,
                    period_start, period_end, projected_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invoiceId,
                subscriptionId,
                grantId,
                invoicePaymentIntent(invoice),
                nullableText(invoice.path("charge")),
                event.eventId(),
                databaseTime(period.start()),
                databaseTime(period.end()),
                databaseTime(now));
        String operationKey = "stripe-invoice:" + invoiceId;
        jdbcTemplate.update(
                """
                INSERT INTO character_entitlement_ledger_entry (
                    entry_id, grant_id, listener_id, operation_key, entry_type,
                    available_delta, reserved_delta, committed_delta, occurred_at
                ) VALUES (?, ?, ?, ?, 'GRANT', ?, 0, 0, ?)
                """,
                UUID.randomUUID(),
                grantId,
                listenerId,
                operationKey,
                properties.monthlyGrantCharacters(),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_operation (
                    operation_key, operation_type, request_fingerprint, outcome, related_id, created_at
                ) VALUES (?, 'DEMONSTRATION_GRANT', ?, 'GRANTED', ?, ?)
                """,
                operationKey,
                event.payloadSha256(),
                grantId,
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_audit_event (
                    event_id, listener_id, event_type, decision, occurred_at
                ) VALUES (?, ?, 'DEMONSTRATION_GRANT_PROJECTED', 'GRANTED', ?)
                """,
                UUID.randomUUID(),
                listenerId,
                databaseTime(now));
        return true;
    }

    private boolean projectSubscriptionState(PendingEvent event) {
        JsonNode subscription = eventObject(event);
        String subscriptionId = requiredText(subscription, "id");
        String listenerReference = nullableText(subscription.path("metadata").path("listener_id"));
        if (listenerReference == null) {
            return false;
        }
        UUID listenerId;
        try {
            listenerId = UUID.fromString(listenerReference);
        } catch (IllegalArgumentException invalidListener) {
            throw new IllegalArgumentException("Subscription has no valid Listener mapping", invalidListener);
        }
        String customerId = requiredText(subscription, "customer");
        String stripeStatus = text(subscription, "status");
        DemonstrationSubscriptionStatus projectedStatus;
        if ("customer.subscription.deleted".equals(event.eventType()) || "canceled".equals(stripeStatus)) {
            projectedStatus = DemonstrationSubscriptionStatus.CANCELED;
        } else if (subscription.path("cancel_at_period_end").asBoolean(false)) {
            projectedStatus = DemonstrationSubscriptionStatus.CANCEL_AT_PERIOD_END;
        } else if ("past_due".equals(stripeStatus)) {
            projectedStatus = DemonstrationSubscriptionStatus.PAST_DUE;
        } else if ("unpaid".equals(stripeStatus)) {
            projectedStatus = DemonstrationSubscriptionStatus.UNPAID;
        } else {
            projectedStatus = DemonstrationSubscriptionStatus.ACTIVE;
        }
        BillingPeriod period = subscriptionPeriod(subscription);
        upsertSubscription(new ProjectedSubscription(
                subscriptionId,
                listenerId,
                customerId,
                projectedStatus,
                period == null ? null : period.start(),
                period == null ? null : period.end(),
                event.eventCreated(),
                event.eventId(),
                clock.instant()));
        return true;
    }

    private ProjectionOutcome projectRefund(PendingEvent event) {
        JsonNode refund = eventObject(event);
        String status = text(refund, "status");
        String refundId = requiredText(refund, "id");
        if ("failed".equals(status) || "canceled".equals(status)) {
            completeOtherRefundEvents(refundId, event.eventId(), ProjectionOutcome.IGNORED);
            return ProjectionOutcome.IGNORED;
        }
        if (!"succeeded".equals(status)) {
            return ProjectionOutcome.DEFERRED;
        }
        String paymentIntentId = nullableText(refund.path("payment_intent"));
        String chargeId = nullableText(refund.path("charge"));
        InvoiceGrant grant = findInvoiceGrant(paymentIntentId, chargeId);
        if (grant == null) {
            return ProjectionOutcome.DEFERRED;
        }
        adjustGrant(
                "stripe-refund:" + refundId,
                AdjustmentKind.REFUND,
                grant,
                event);
        completeOtherRefundEvents(refundId, event.eventId(), ProjectionOutcome.IGNORED);
        return ProjectionOutcome.PROJECTED;
    }

    private ProjectionOutcome projectInvoiceCorrection(PendingEvent event) {
        String invoiceId = requiredText(eventObject(event), "id");
        InvoiceGrant grant = findInvoiceGrant(invoiceId);
        if (grant == null) {
            return ProjectionOutcome.DEFERRED;
        }
        adjustGrant(
                "stripe-void:" + invoiceId,
                AdjustmentKind.VOID,
                grant,
                event);
        return ProjectionOutcome.PROJECTED;
    }

    private void completeEvent(String eventId, ProjectionOutcome outcome) {
        jdbcTemplate.update(
                """
                UPDATE stripe_demonstration_event_inbox
                SET projection_status = ?, projected_at = ?
                WHERE event_id = ?
                """,
                outcome.name(),
                databaseTime(clock.instant()),
                eventId);
    }

    private void completeOtherRefundEvents(
            String refundId,
            String currentEventId,
            ProjectionOutcome outcome) {
        jdbcTemplate.update(
                """
                UPDATE stripe_demonstration_event_inbox
                SET projection_status = ?, projected_at = ?
                WHERE projection_status = 'PENDING'
                  AND event_type IN ('refund.created', 'refund.updated')
                  AND payload #>> '{data,object,id}' = ?
                  AND event_id <> ?
                """,
                outcome.name(),
                databaseTime(clock.instant()),
                refundId,
                currentEventId);
    }

    private static ProjectionOutcome outcome(boolean projected) {
        return projected ? ProjectionOutcome.PROJECTED : ProjectionOutcome.IGNORED;
    }

    private boolean adjustGrant(
            String adjustmentReference,
            AdjustmentKind adjustmentKind,
            InvoiceGrant grant,
            PendingEvent event) {
        lockEntitlementState();
        if (exists(
                "demonstration_subscription_grant_adjustment",
                "adjustment_reference",
                adjustmentReference)) {
            return true;
        }
        Instant now = clock.instant();
        long available = jdbcTemplate.queryForObject(
                """
                SELECT GREATEST(COALESCE(SUM(available_delta), 0), 0)
                FROM character_entitlement_ledger_entry
                WHERE grant_id = ?
                """,
                Long.class,
                grant.grantId());
        jdbcTemplate.update(
                """
                INSERT INTO demonstration_subscription_grant_adjustment (
                    adjustment_reference, stripe_invoice_id, grant_id,
                    adjustment_kind, stripe_event_id, projected_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                adjustmentReference,
                grant.invoiceId(),
                grant.grantId(),
                adjustmentKind.name(),
                event.eventId(),
                databaseTime(now));
        if (available > 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO character_entitlement_ledger_entry (
                        entry_id, grant_id, listener_id, operation_key, entry_type,
                        available_delta, reserved_delta, committed_delta, occurred_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?)
                    """,
                    UUID.randomUUID(),
                    grant.grantId(),
                    grant.listenerId(),
                    adjustmentReference,
                    adjustmentKind.name(),
                    -available,
                    databaseTime(now));
        }
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_operation (
                    operation_key, operation_type, request_fingerprint, outcome, related_id, created_at
                ) VALUES (?, 'DEMONSTRATION_ADJUSTMENT', ?, ?, ?, ?)
                """,
                adjustmentReference,
                event.payloadSha256(),
                adjustmentKind.name(),
                grant.grantId(),
                databaseTime(now));
        jdbcTemplate.update(
                """
                INSERT INTO entitlement_audit_event (
                    event_id, listener_id, event_type, decision, occurred_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                grant.listenerId(),
                adjustmentKind.auditType(),
                adjustmentKind.name(),
                databaseTime(now));
        return true;
    }

    private BillingPeriod billingPeriod(JsonNode invoice) {
        for (JsonNode line : invoice.path("lines").path("data")) {
            String priceId = firstText(
                    line.path("pricing").path("price_details").path("price"),
                    line.path("price").path("id"));
            if (properties.monthlyPriceId().equals(priceId)) {
                long start = line.path("period").path("start").asLong(0);
                long end = line.path("period").path("end").asLong(0);
                if (start > 0 && end > start) {
                    return new BillingPeriod(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end));
                }
            }
        }
        return null;
    }

    private BillingPeriod subscriptionPeriod(JsonNode subscription) {
        for (JsonNode item : subscription.path("items").path("data")) {
            long start = item.path("current_period_start").asLong(0);
            long end = item.path("current_period_end").asLong(0);
            if (start > 0 && end > start) {
                return new BillingPeriod(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end));
            }
        }
        long start = subscription.path("current_period_start").asLong(0);
        long end = subscription.path("current_period_end").asLong(0);
        return start > 0 && end > start
                ? new BillingPeriod(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end))
                : null;
    }

    private String invoicePaymentIntent(JsonNode invoice) {
        String direct = nullableText(invoice.path("payment_intent"));
        if (direct != null) {
            return direct;
        }
        for (JsonNode payment : invoice.path("payments").path("data")) {
            String paymentIntent = nullableText(payment.path("payment").path("payment_intent"));
            if (paymentIntent != null) {
                return paymentIntent;
            }
        }
        return null;
    }

    private void upsertSubscription(ProjectedSubscription subscription) {
        jdbcTemplate.update(
                """
                INSERT INTO demonstration_subscription (
                    stripe_subscription_id, listener_id, stripe_customer_id, subscription_status,
                    current_period_start, current_period_end, latest_event_created,
                    latest_event_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stripe_subscription_id) DO UPDATE SET
                    subscription_status = EXCLUDED.subscription_status,
                    current_period_start = COALESCE(EXCLUDED.current_period_start, demonstration_subscription.current_period_start),
                    current_period_end = COALESCE(EXCLUDED.current_period_end, demonstration_subscription.current_period_end),
                    latest_event_created = EXCLUDED.latest_event_created,
                    latest_event_id = EXCLUDED.latest_event_id,
                    updated_at = EXCLUDED.updated_at
                WHERE (demonstration_subscription.latest_event_created, demonstration_subscription.latest_event_id)
                    < (EXCLUDED.latest_event_created, EXCLUDED.latest_event_id)
                """,
                subscription.subscriptionId(),
                subscription.listenerId(),
                subscription.customerId(),
                subscription.status().name(),
                subscription.periodStart() == null ? null : databaseTime(subscription.periodStart()),
                subscription.periodEnd() == null ? null : databaseTime(subscription.periodEnd()),
                databaseTime(subscription.eventCreated()),
                subscription.eventId(),
                databaseTime(subscription.projectedAt()));
        SubscriptionOwner storedOwner = jdbcTemplate.queryForObject(
                """
                SELECT listener_id, stripe_customer_id
                FROM demonstration_subscription
                WHERE stripe_subscription_id = ?
                """,
                (resultSet, rowNumber) -> new SubscriptionOwner(
                        resultSet.getObject("listener_id", UUID.class),
                        resultSet.getString("stripe_customer_id")),
                subscription.subscriptionId());
        if (storedOwner == null
                || !storedOwner.listenerId().equals(subscription.listenerId())
                || !storedOwner.customerId().equals(subscription.customerId())) {
            throw new IllegalArgumentException("Demonstration Subscription ownership cannot change");
        }
    }

    private JsonNode eventObject(PendingEvent event) {
        try {
            return objectMapper.readTree(event.payload()).path("data").path("object");
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("Verified Stripe event could not be parsed", invalidJson);
        }
    }

    private boolean exists(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class,
                value);
        return count != null && count > 0;
    }

    private InvoiceGrant findInvoiceGrant(String invoiceId) {
        return jdbcTemplate.query(
                """
                SELECT i.stripe_invoice_id, i.grant_id, g.listener_id
                FROM demonstration_subscription_invoice_grant i
                JOIN conversion_entitlement_grant g ON g.grant_id = i.grant_id
                WHERE i.stripe_invoice_id = ?
                """,
                resultSet -> resultSet.next()
                        ? new InvoiceGrant(
                                resultSet.getString("stripe_invoice_id"),
                                resultSet.getObject("grant_id", UUID.class),
                                resultSet.getObject("listener_id", UUID.class))
                        : null,
                invoiceId);
    }

    private InvoiceGrant findInvoiceGrant(String paymentIntentId, String chargeId) {
        if (paymentIntentId == null && chargeId == null) {
            return null;
        }
        String predicate = paymentIntentId != null
                ? "i.stripe_payment_intent_id = ?"
                : "i.stripe_charge_id = ?";
        String reference = paymentIntentId != null ? paymentIntentId : chargeId;
        return jdbcTemplate.query(
                """
                SELECT i.stripe_invoice_id, i.grant_id, g.listener_id
                FROM demonstration_subscription_invoice_grant i
                JOIN conversion_entitlement_grant g ON g.grant_id = i.grant_id
                WHERE %s
                LIMIT 1
                """.formatted(predicate),
                resultSet -> resultSet.next()
                        ? new InvoiceGrant(
                                resultSet.getString("stripe_invoice_id"),
                                resultSet.getObject("grant_id", UUID.class),
                                resultSet.getObject("listener_id", UUID.class))
                        : null,
                reference);
    }

    private void lockEntitlementState() {
        jdbcTemplate.queryForObject(
                "SELECT lock_id FROM entitlement_transaction_lock WHERE lock_id = 1 FOR UPDATE",
                Short.class);
    }

    private static String requiredText(JsonNode node, String field) {
        String value = nullableText(node.path(field));
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("Stripe " + field + " is invalid");
        }
        return value;
    }

    private static String firstText(JsonNode... candidates) {
        String value = null;
        for (JsonNode candidate : candidates) {
            value = nullableText(candidate);
            if (value != null) {
                break;
            }
        }
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("Stripe event reference is invalid");
        }
        return value;
    }

    private static String nullableText(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank() ? node.textValue() : null;
    }

    private static String text(JsonNode node, String field) {
        return nullableText(node.path(field));
    }

    private static OffsetDateTime databaseTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record PendingEvent(
            String eventId,
            String eventType,
            Instant eventCreated,
            String payload,
            String payloadSha256) {
    }

    private record BillingPeriod(Instant start, Instant end) {
    }

    private record InvoiceGrant(String invoiceId, UUID grantId, UUID listenerId) {
    }

    private record ProjectedSubscription(
            String subscriptionId,
            UUID listenerId,
            String customerId,
            DemonstrationSubscriptionStatus status,
            Instant periodStart,
            Instant periodEnd,
            Instant eventCreated,
            String eventId,
            Instant projectedAt) {
    }

    private record SubscriptionOwner(UUID listenerId, String customerId) {
    }

    private enum AdjustmentKind {
        REFUND("DEMONSTRATION_GRANT_REFUNDED"),
        VOID("DEMONSTRATION_GRANT_CORRECTED");

        private final String auditType;

        AdjustmentKind(String auditType) {
            this.auditType = auditType;
        }

        String auditType() {
            return auditType;
        }
    }

    private enum DemonstrationSubscriptionStatus {
        ACTIVE,
        CANCEL_AT_PERIOD_END,
        PAST_DUE,
        UNPAID,
        CANCELED
    }

    private enum ProjectionOutcome {
        PROJECTED,
        IGNORED,
        DEFERRED
    }
}
