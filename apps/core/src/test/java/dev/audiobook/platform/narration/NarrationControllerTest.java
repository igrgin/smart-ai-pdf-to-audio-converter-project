package dev.audiobook.platform.narration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.ListenerIdentityService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import dev.audiobook.platform.workflow.InspectionWorkflowService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class NarrationControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000028");
    private static final UUID CONVERSION_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000128");
    private static final UUID VOICE_ID = UUID.fromString("10000000-0000-7000-8000-000000000001");

    @MockitoBean
    private NarrationSelectionService narrationSelectionService;

    @MockitoBean
    private AudiobookConversionService audiobookConversionService;

    @MockitoBean
    private InspectionWorkflowService inspectionWorkflowService;

    @MockitoBean
    private ConversionEntitlementService entitlementService;

    @MockitoBean
    private ListenerIdentityService listenerIdentityService;

    @MockitoBean
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listenerComparesAProviderNeutralCatalogWithOneStandardPreviewPassage() throws Exception {
        when(narrationSelectionService.catalog()).thenReturn(new NarrationSelectionService.VoiceCatalog(
                List.of(
                        voice(VOICE_ID, "Rowan", "British English", "Warm", "Grounded"),
                        voice("10000000-0000-7000-8000-000000000002", "Marlowe", "American English", "Clear", "Assured"),
                        voice("10000000-0000-7000-8000-000000000003", "Ellis", "Irish English", "Bright", "Expressive"),
                        voice("10000000-0000-7000-8000-000000000004", "Clara", "British English", "Calm", "Intimate"),
                        voice("10000000-0000-7000-8000-000000000005", "Ansel", "Australian English", "Open", "Conversational"),
                        voice("10000000-0000-7000-8000-000000000006", "Sloane", "American English", "Poised", "Reflective")),
                List.of(
                        NarrationSelectionService.NarrationPace.MEASURED,
                        NarrationSelectionService.NarrationPace.NATURAL,
                        NarrationSelectionService.NarrationPace.BRISK),
                NarrationSelectionService.NarrationPace.NATURAL));

        mockMvc.perform(get("/api/v1/narrator-voices").with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voices.length()").value(6))
                .andExpect(jsonPath("$.voices[0].id").value(VOICE_ID.toString()))
                .andExpect(jsonPath("$.voices[0].displayName").value("Rowan"))
                .andExpect(jsonPath("$.voices[0].englishVariety").value("British English"))
                .andExpect(jsonPath("$.voices[0].descriptors[0]").value("Warm"))
                .andExpect(jsonPath("$.voices[0].descriptorReviewVersion").value("voice-review-2026-07"))
                .andExpect(jsonPath("$.voices[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.voices[0].preview.passageVersion").value("folio-preview-v1"))
                .andExpect(jsonPath("$.voices[0].preview.durationSeconds").value(27))
                .andExpect(jsonPath("$.voices[5].preview.passageVersion").value("folio-preview-v1"))
                .andExpect(jsonPath("$.paces[0]").value("MEASURED"))
                .andExpect(jsonPath("$.paces[1]").value("NATURAL"))
                .andExpect(jsonPath("$.paces[2]").value("BRISK"))
                .andExpect(jsonPath("$.defaultPace").value("NATURAL"))
                .andExpect(jsonPath("$.voices[0].provider").doesNotExist())
                .andExpect(jsonPath("$.voices[0].model").doesNotExist());
    }

    @Test
    void listenerConfirmsAnIssuedVoiceAndExactPaceAgainstTheSeenConversionVersion() throws Exception {
        UUID recipeId = UUID.fromString("01985f42-5f8d-7000-8000-000000000228");
        when(narrationSelectionService.confirm(new NarrationSelectionService.ConfirmCommand(
                        LISTENER_ID,
                        CONVERSION_ID,
                        VOICE_ID,
                        NarrationSelectionService.NarrationPace.BRISK,
                        0,
                        "confirm-narration-28")))
                .thenReturn(new NarrationSelectionService.ConfirmedRecipe(
                        recipeId, CONVERSION_ID, VOICE_ID, "Rowan", NarrationSelectionService.NarrationPace.BRISK,
                        "b".repeat(64), 1));

        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/generation-recipe")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "confirm-narration-28")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voiceId":"%s","pace":"BRISK"}
                                """.formatted(VOICE_ID))
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", "/api/v1/audiobook-conversions/" + CONVERSION_ID + "/generation-recipe"))
                .andExpect(jsonPath("$.recipeId").value(recipeId.toString()))
                .andExpect(jsonPath("$.voiceId").value(VOICE_ID.toString()))
                .andExpect(jsonPath("$.voiceDisplayName").value("Rowan"))
                .andExpect(jsonPath("$.pace").value("BRISK"))
                .andExpect(jsonPath("$.recipeDigest").value("b".repeat(64)))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.modelSnapshot").doesNotExist());

        verify(narrationSelectionService).confirm(new NarrationSelectionService.ConfirmCommand(
                LISTENER_ID,
                CONVERSION_ID,
                VOICE_ID,
                NarrationSelectionService.NarrationPace.BRISK,
                0,
                "confirm-narration-28"));
    }

    @Test
    void unsupportedPaceFailsAtTheHttpBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/generation-recipe")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "confirm-narration-28")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voiceId":"%s","pace":"FAST"}
                                """.formatted(VOICE_ID))
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/audiobook-conversions/" + CONVERSION_ID + "/generation-recipe")
                        .header("Origin", "http://localhost:3000")
                        .header("Idempotency-Key", "confirm-narration-without-voice-28")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pace\":\"NATURAL\"}")
                        .with(authentication(listenerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private static NarrationSelectionService.NarratorVoice voice(
            String id, String name, String variety, String firstDescriptor, String secondDescriptor) {
        return voice(UUID.fromString(id), name, variety, firstDescriptor, secondDescriptor);
    }

    private static NarrationSelectionService.NarratorVoice voice(
            UUID id, String name, String variety, String firstDescriptor, String secondDescriptor) {
        return new NarrationSelectionService.NarratorVoice(
                id,
                name,
                variety,
                List.of(firstDescriptor, secondDescriptor),
                "voice-review-2026-07",
                NarrationSelectionService.VoiceAvailability.AVAILABLE,
                new NarrationSelectionService.VoicePreview(
                        "/samples/midnight-library-of-small-beginnings.mp3",
                        "folio-preview-v1",
                        27,
                        true));
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal = new ListenerPrincipal(
                LISTENER_ID,
                "Narration Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }
}
