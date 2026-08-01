package dev.audiobook.platform.entitlement;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.PublicationSubmissionService;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(
        classes = PlatformApplication.class,
        properties = "platform.demonstration-subscription.webhook-secret=whsec_test_webhook")
class StripeDemonstrationSubscriptionWebhookControllerTest {

    private static final String WEBHOOK_PATH = "/api/v1/integrations/stripe/events";

    @MockitoBean
    private ConversionEntitlementService entitlementService;

    @MockitoBean
    private PublicationSubmissionService submissionService;

    @MockitoBean
    private AudiobookConversionService audiobookConversionService;

    @MockitoBean
    private ListenerIdentityService listenerIdentityService;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private StripeEventInboxService inboxService;

    @MockitoBean
    private DemonstrationSubscriptionProjector projector;

    @MockitoBean
    private DemonstrationSubscriptionProjectorControlService projectorControlService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsAnAuthenticStripeTestModeEventWithoutAListenerSessionOrBrowserOrigin() throws Exception {
        String payload = """
                {"id":"evt_demo_paid","object":"event","created":1785592800,"livemode":false,
                 "type":"invoice.paid","data":{"object":{"id":"in_demo_paid"}}}
                """;

        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", signature(payload, "whsec_test_webhook"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsInvalidSignaturesAndLiveModeBeforeTheInbox() throws Exception {
        String sandboxPayload = """
                {"id":"evt_bad_signature","object":"event","created":1785592800,"livemode":false,
                 "type":"invoice.paid","data":{"object":{"id":"in_bad_signature"}}}
                """;
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", "t=" + Instant.now().getEpochSecond() + ",v1=00")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sandboxPayload))
                .andExpect(status().isBadRequest());

        String livePayload = """
                {"id":"evt_live","object":"event","created":1785592800,"livemode":true,
                 "type":"invoice.paid","data":{"object":{"id":"in_live"}}}
                """;
        mockMvc.perform(post(WEBHOOK_PATH)
                        .header("Stripe-Signature", signature(livePayload, "whsec_test_webhook"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(livePayload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inboxService, projector);
    }

    private static String signature(String payload, String secret) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + java.util.HexFormat.of().formatHex(digest);
    }
}
