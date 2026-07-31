package dev.audiobook.platform.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.identity.ExternalIdentity;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.SignInProvider;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("itest")
@SpringBootTest(classes = PlatformApplication.class)
class ConversionEntitlementConcurrencyITest {

    private final ConversionEntitlementService entitlementService;
    private final ListenerIdentityService listenerIdentityService;

    @Autowired
    ConversionEntitlementConcurrencyITest(
            ConversionEntitlementService entitlementService,
            ListenerIdentityService listenerIdentityService) {
        this.entitlementService = entitlementService;
        this.listenerIdentityService = listenerIdentityService;
    }

    @Test
    void concurrentAdmissionsReadCurrentStateAndCannotOversubscribeOneListener() throws Exception {
        UUID listenerId = listenerIdentityService.establish(new ExternalIdentity(
                        URI.create("https://accounts.google.com"),
                        "concurrent-entitlement-" + UUID.randomUUID(),
                        SignInProvider.GOOGLE,
                        null,
                        "Concurrent Entitlement Listener"))
                .listenerId();
        entitlementService.approveFreeGrant(
                listenerId, "concurrent-approval-" + listenerId, "concurrent-grant-" + listenerId);
        CountDownLatch start = new CountDownLatch(1);

        List<ConversionEntitlementService.AdmissionDecision> decisions;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ConversionEntitlementService.AdmissionDecision> first = executor.submit(
                    () -> authorize(listenerId, "first", start));
            Future<ConversionEntitlementService.AdmissionDecision> second = executor.submit(
                    () -> authorize(listenerId, "second", start));
            start.countDown();
            decisions = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        assertThat(decisions).filteredOn(ConversionEntitlementService.AdmissionDecision::authorized).hasSize(1);
        assertThat(decisions)
                .filteredOn(decision -> !decision.authorized())
                .extracting(ConversionEntitlementService.AdmissionDecision::denial)
                .containsExactly(ConversionEntitlementService.AdmissionDenial.LISTENER_CONCURRENCY_LIMIT);

        ConversionEntitlementService.AdmissionDecision authorized = decisions.stream()
                .filter(ConversionEntitlementService.AdmissionDecision::authorized)
                .findFirst()
                .orElseThrow();
        entitlementService.settle(new ConversionEntitlementService.SettlementRequest(
                authorized.reservationId(), 0, 0, "concurrent-settlement-" + listenerId));
        assertThat(entitlementService.allowance(listenerId).availableCharacters()).isEqualTo(500_000);
    }

    private ConversionEntitlementService.AdmissionDecision authorize(
            UUID listenerId,
            String suffix,
            CountDownLatch start) throws InterruptedException {
        start.await(10, TimeUnit.SECONDS);
        return entitlementService.authorizeSpeech(new ConversionEntitlementService.AdmissionRequest(
                listenerId,
                UUID.randomUUID(),
                "openai",
                "recipe-free-v1",
                "rates-2026-08",
                100_000,
                500_000,
                "concurrent-admission-" + suffix + "-" + listenerId));
    }
}
