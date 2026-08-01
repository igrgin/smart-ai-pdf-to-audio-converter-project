package dev.audiobook.platform.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("itest")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class DemonstrationSubscriptionProjectionITest {

    private static final String WEBHOOK_PATH = "/api/v1/integrations/stripe/events";
    private static final String WEBHOOK_SECRET = "whsec_test_webhook";

    private final MockMvc mockMvc;
    private final ListenerIdentityService listenerIdentityService;
    private final ConversionEntitlementService entitlementService;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    DemonstrationSubscriptionProjectionITest(
            MockMvc mockMvc,
            ListenerIdentityService listenerIdentityService,
            ConversionEntitlementService entitlementService,
            JdbcTemplate jdbcTemplate) {
        this.mockMvc = mockMvc;
        this.listenerIdentityService = listenerIdentityService;
        this.entitlementService = entitlementService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void verifiedPaidInvoiceProjectsExactlyOneLocalMonthlyGrant() throws Exception {
        UUID listenerId = listener("paid-invoice");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String firstDelivery = paidInvoiceEvent(
                "evt_paid_first", "in_paid_once", "sub_paid_once", listenerId, now, now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS));

        deliver(firstDelivery);
        deliver(firstDelivery);
        deliver(paidInvoiceEvent(
                "evt_paid_duplicate", "in_paid_once", "sub_paid_once", listenerId, now.plusSeconds(1), now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS)));

        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::status,
                        ConversionEntitlementService.Allowance::grantedCharacters,
                        ConversionEntitlementService.Allowance::availableCharacters)
                .containsExactly(ConversionEntitlementService.AllowanceStatus.AVAILABLE, 500_000L, 500_000L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM stripe_demonstration_event_inbox WHERE event_id IN ('evt_paid_first', 'evt_paid_duplicate')",
                        Long.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM demonstration_subscription_invoice_grant WHERE stripe_invoice_id = 'in_paid_once'",
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void paidEventCannotAuthorizeWhileProjectionIsPaused() throws Exception {
        UUID listenerId = listener("paused-projector");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        pauseProjector();
        try {
            deliver(paidInvoiceEvent(
                    "evt_paid_paused", "in_paid_paused", "sub_paid_paused", listenerId, now,
                    now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS)));

            assertThat(entitlementService.allowance(listenerId).status())
                    .isEqualTo(ConversionEntitlementService.AllowanceStatus.NO_GRANT);
            ConversionEntitlementService.AdmissionDecision denied = entitlementService.authorizeSpeech(
                    new ConversionEntitlementService.AdmissionRequest(
                            listenerId,
                            UUID.randomUUID(),
                            "openai",
                            "recipe-demo-v1",
                            "rates-2026-08",
                            1,
                            1,
                            "paid-but-unprojected"));
            assertThat(denied.denial()).isEqualTo(ConversionEntitlementService.AdmissionDenial.NO_GRANT);
            assertThat(jdbcTemplate.queryForObject(
                            "SELECT projection_status FROM stripe_demonstration_event_inbox WHERE event_id = 'evt_paid_paused'",
                            String.class))
                    .isEqualTo("PENDING");
        } finally {
            resumeProjector();
        }

        assertThat(entitlementService.allowance(listenerId).availableCharacters()).isEqualTo(500_000);
    }

    @Test
    void renewalCancellationRefundAndCorrectionRemainLocalAndNeverMakeUsageNegative() throws Exception {
        UUID listenerId = listener("test-clock-lifecycle");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        deliver(paidInvoiceEvent(
                "evt_clock_initial", "in_clock_initial", "sub_clock", listenerId, now.minusSeconds(10),
                now.minus(31, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)));
        deliver(paidInvoiceEvent(
                "evt_clock_renewal", "in_clock_renewal", "sub_clock", listenerId, now,
                now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS)));

        assertThat(entitlementService.allowance(listenerId).availableCharacters()).isEqualTo(500_000);
        ConversionEntitlementService.AdmissionDecision admission = entitlementService.authorizeSpeech(
                new ConversionEntitlementService.AdmissionRequest(
                        listenerId,
                        UUID.randomUUID(),
                        "openai",
                        "recipe-demo-v1",
                        "rates-2026-08",
                        100_000,
                        10,
                        "clock-lifecycle-admission"));
        entitlementService.settle(new ConversionEntitlementService.SettlementRequest(
                admission.reservationId(), 100_000, 10, "clock-lifecycle-settlement"));

        deliver(subscriptionEvent(
                "evt_subscription_canceling", "sub_clock", listenerId, now.plusSeconds(20), true, "active"));
        deliver(subscriptionEvent(
                "evt_subscription_stale_active", "sub_clock", listenerId, now.plusSeconds(5), false, "active"));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT subscription_status FROM demonstration_subscription WHERE stripe_subscription_id = 'sub_clock'",
                        String.class))
                .isEqualTo("CANCEL_AT_PERIOD_END");
        assertThat(entitlementService.allowance(listenerId).availableCharacters()).isEqualTo(400_000);

        deliver(refundEvent(
                "evt_refund_created", "re_clock", "pi_in_clock_renewal", now.plusSeconds(30), "succeeded"));
        deliver(refundEvent(
                "evt_refund_replay", "re_clock", "pi_in_clock_renewal", now.plusSeconds(31), "succeeded"));

        assertThat(entitlementService.allowance(listenerId))
                .extracting(
                        ConversionEntitlementService.Allowance::availableCharacters,
                        ConversionEntitlementService.Allowance::committedCharacters)
                .containsExactly(0L, 100_000L);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM demonstration_subscription_grant_adjustment WHERE adjustment_reference = 'stripe-refund:re_clock'",
                        Long.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT MIN(committed_characters) FROM (SELECT SUM(committed_delta) committed_characters FROM character_entitlement_ledger_entry GROUP BY grant_id) totals",
                        Long.class))
                .isGreaterThanOrEqualTo(0);

        UUID correctedListener = listener("invoice-correction");
        deliver(paidInvoiceEvent(
                "evt_corrected_paid", "in_corrected", "sub_corrected", correctedListener, now,
                now.minus(1, ChronoUnit.DAYS), now.plus(29, ChronoUnit.DAYS)));
        deliver(invoiceCorrectionEvent("evt_corrected_void", "invoice.voided", "in_corrected", now.plusSeconds(40)));
        assertThat(entitlementService.allowance(correctedListener).availableCharacters()).isZero();
    }

    private void deliver(String payload) throws Exception {
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", signature(payload))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    private void pauseProjector() throws Exception {
        mockMvc.perform(post("/api/v1/operator/demonstration-subscriptions/projector/pause")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private void resumeProjector() throws Exception {
        mockMvc.perform(post("/api/v1/operator/demonstration-subscriptions/projector/resume")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(operatorAuthentication()))
                        .with(csrf()))
                .andExpect(status().isNoContent());
    }

    private static UsernamePasswordAuthenticationToken operatorAuthentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
    }

    private UUID listener(String subject) {
        return listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "stripe-" + subject + "-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Demonstration Listener"))
                .listenerId();
    }

    private static String paidInvoiceEvent(
            String eventId,
            String invoiceId,
            String subscriptionId,
            UUID listenerId,
            Instant eventCreated,
            Instant periodStart,
            Instant periodEnd) {
        return """
                {
                  "id":"%s","object":"event","created":%d,"livemode":false,"type":"invoice.paid",
                  "data":{"object":{
                    "id":"%s","object":"invoice","customer":"cus_demo","status":"paid","paid":true,
                    "billing_reason":"subscription_cycle","payment_intent":"pi_%s","charge":"ch_%s",
                    "parent":{"type":"subscription_details","subscription_details":{
                      "subscription":"%s","metadata":{"listener_id":"%s"}}},
                    "lines":{"data":[{"period":{"start":%d,"end":%d},
                      "pricing":{"price_details":{"price":"price_folio_demo_monthly"}}}]}
                  }}
                }
                """.formatted(
                eventId,
                eventCreated.getEpochSecond(),
                invoiceId,
                invoiceId,
                invoiceId,
                subscriptionId,
                listenerId,
                periodStart.getEpochSecond(),
                periodEnd.getEpochSecond());
    }

    private static String subscriptionEvent(
            String eventId,
            String subscriptionId,
            UUID listenerId,
            Instant eventCreated,
            boolean cancelAtPeriodEnd,
            String status) {
        return """
                {"id":"%s","object":"event","created":%d,"livemode":false,
                 "type":"customer.subscription.updated","data":{"object":{
                   "id":"%s","object":"subscription","customer":"cus_demo","status":"%s",
                   "cancel_at_period_end":%s,"metadata":{"listener_id":"%s"},
                   "items":{"data":[{"current_period_start":%d,"current_period_end":%d}]}
                 }}}
                """.formatted(
                eventId,
                eventCreated.getEpochSecond(),
                subscriptionId,
                status,
                cancelAtPeriodEnd,
                listenerId,
                eventCreated.minus(1, ChronoUnit.DAYS).getEpochSecond(),
                eventCreated.plus(29, ChronoUnit.DAYS).getEpochSecond());
    }

    private static String refundEvent(
            String eventId,
            String refundId,
            String paymentIntentId,
            Instant eventCreated,
            String status) {
        return """
                {"id":"%s","object":"event","created":%d,"livemode":false,
                 "type":"refund.created","data":{"object":{
                   "id":"%s","object":"refund","payment_intent":"%s","status":"%s"
                 }}}
                """.formatted(eventId, eventCreated.getEpochSecond(), refundId, paymentIntentId, status);
    }

    private static String invoiceCorrectionEvent(
            String eventId,
            String eventType,
            String invoiceId,
            Instant eventCreated) {
        return """
                {"id":"%s","object":"event","created":%d,"livemode":false,
                 "type":"%s","data":{"object":{"id":"%s","object":"invoice"}}}
                """.formatted(eventId, eventCreated.getEpochSecond(), eventType, invoiceId);
    }

    private static String signature(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
    }
}
