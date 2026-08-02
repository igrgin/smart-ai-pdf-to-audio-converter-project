package dev.audiobook.platform.bootstrap.composition.entitlement.internal.stripe;

import dev.audiobook.platform.entitlement.internal.stripe.*;

import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.entitlement.StripeWebhookTestEvents;
import dev.audiobook.platform.entitlement.internal.subscription.DemonstrationSubscriptionProjector;
import dev.audiobook.platform.entitlement.internal.subscription.DemonstrationSubscriptionProjectorControlService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.internal.submission.PublicationSubmissionService;
import dev.audiobook.platform.identity.internal.session.ListenerIdentityService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.time.Instant;
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
                        .header("Stripe-Signature", StripeWebhookTestEvents.signature(payload, "whsec_test_webhook"))
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
                        .header("Stripe-Signature", StripeWebhookTestEvents.signature(livePayload, "whsec_test_webhook"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(livePayload))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inboxService, projector);
    }

}
