package dev.audiobook.platform.bootstrap.composition.library.internal;

import dev.audiobook.platform.library.internal.*;

import dev.audiobook.platform.library.PrivateAudiobookLibraryService;
import dev.audiobook.platform.library.internal.playback.UnsatisfiedRangeException;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.admission.internal.submission.PublicationSubmissionService;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.identity.internal.listener.ListenerIdentityService;
import dev.audiobook.platform.identity.ListenerPrincipal;
import dev.audiobook.platform.identity.SignInProvider;
import dev.audiobook.platform.narration.NarrationSelectionService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
class PrivateAudiobookPlaybackControllerTest {

    private static final UUID LISTENER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000032");
    private static final UUID AUDIOBOOK_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000132");
    private static final UUID ASSET_VERSION_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000232");
    private static final UUID CONVERSION_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000332");
    private static final UUID CHAPTER_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000432");
    private static final UUID PART_ID = UUID.fromString("01985f42-5f8d-7000-8000-000000000532");
    private static final String MANIFEST_PATH = "/api/v1/audiobooks/" + AUDIOBOOK_ID
            + "/asset-versions/" + ASSET_VERSION_ID + "/manifest";
    private static final String MEDIA_PATH = "/api/v1/audiobooks/" + AUDIOBOOK_ID
            + "/asset-versions/" + ASSET_VERSION_ID + "/parts/" + PART_ID + "/media";
    private static final String POSITION_PATH = "/api/v1/audiobooks/" + AUDIOBOOK_ID
            + "/asset-versions/" + ASSET_VERSION_ID + "/playback-position";

    @MockitoBean
    private PrivateAudiobookLibraryService libraryService;

    @MockitoBean
    private ListenerIdentityService listenerIdentityService;

    @MockitoBean
    private ConversionEntitlementService conversionEntitlementService;

    @MockitoBean
    private PublicationSubmissionService publicationSubmissionService;

    @MockitoBean
    private AudiobookConversionService audiobookConversionService;

    @MockitoBean
    private NarrationSelectionService narrationSelectionService;

    @MockitoBean
    private DataSource dataSource;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void manifestPublishesTheExactImmutablePrivateRepresentation() throws Exception {
        String digest = "a".repeat(64);
        when(libraryService.manifest(LISTENER_ID, AUDIOBOOK_ID, ASSET_VERSION_ID))
                .thenReturn(new PrivateAudiobookLibraryService.PlaybackManifest(
                        AUDIOBOOK_ID,
                        ASSET_VERSION_ID,
                        CONVERSION_ID,
                        "application/pdf",
                        "Rowan",
                        digest,
                        10_000,
                        new PrivateAudiobookLibraryService.ResumePosition(4_200, 3),
                        List.of(new PrivateAudiobookLibraryService.PlaybackChapter(
                                CHAPTER_ID,
                                0,
                                "First light",
                                0,
                                10_000,
                                List.of(new PrivateAudiobookLibraryService.PlaybackPart(
                                        PART_ID,
                                        0,
                                        10,
                                        10_000,
                                        "audio/mpeg",
                                        "sha256:" + "b".repeat(64),
                                        MEDIA_PATH))))));

        mockMvc.perform(get(MANIFEST_PATH).with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", '"' + "sha256:" + digest + '"'))
                .andExpect(jsonPath("$.resume.positionMs").value(4200))
                .andExpect(jsonPath("$.chapters[0].parts[0].mediaUrl").value(MEDIA_PATH));
    }

    @Test
    void mediaMapsOneVerifiedRangeAndAnUnsatisfiedRangeToHttpSemantics() throws Exception {
        String digest = "b".repeat(64);
        when(libraryService.media(
                        LISTENER_ID, AUDIOBOOK_ID, ASSET_VERSION_ID, PART_ID,
                        "bytes=2-5", null, false))
                .thenReturn(new PrivateAudiobookLibraryService.MediaResponse(
                        "audio/mpeg", "sha256:" + digest, 10, 2L, 5L, new byte[] {2, 3, 4, 5}));

        mockMvc.perform(get(MEDIA_PATH)
                        .header("Range", "bytes=2-5")
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 2-5/10"))
                .andExpect(header().string("ETag", '"' + "sha256:" + digest + '"'))
                .andExpect(content().bytes(new byte[] {2, 3, 4, 5}));

        when(libraryService.media(
                        LISTENER_ID, AUDIOBOOK_ID, ASSET_VERSION_ID, PART_ID,
                        "bytes=20-30", null, false))
                .thenThrow(new UnsatisfiedRangeException(10));
        mockMvc.perform(get(MEDIA_PATH)
                        .header("Range", "bytes=20-30")
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */10"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void positionUpdateRequiresAndForwardsTheExactConditionalMutationContract() throws Exception {
        PrivateAudiobookLibraryService.UpdatePosition command =
                new PrivateAudiobookLibraryService.UpdatePosition(
                        LISTENER_ID, AUDIOBOOK_ID, ASSET_VERSION_ID, 4_200, 3, "position-32");
        when(libraryService.updatePosition(command))
                .thenReturn(new PrivateAudiobookLibraryService.ResumePosition(4_200, 4));

        mockMvc.perform(put(POSITION_PATH)
                        .header("Origin", "http://localhost:3000")
                        .header("If-Match", "\"3\"")
                        .header("Idempotency-Key", "position-32")
                        .contentType("application/json")
                        .content("{\"positionMs\":4200}")
                        .with(csrf())
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.version").value(4));
        verify(libraryService).updatePosition(command);

        mockMvc.perform(put(POSITION_PATH)
                        .header("Origin", "http://localhost:3000")
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "malformed-version")
                        .contentType("application/json")
                        .content("{\"positionMs\":4200}")
                        .with(csrf())
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(put(POSITION_PATH)
                        .header("Origin", "http://localhost:3000")
                        .header("If-Match", "\"3\"")
                        .contentType("application/json")
                        .content("{\"positionMs\":4200}")
                        .with(csrf())
                        .with(authentication(listenerAuthentication())))
                .andExpect(status().isBadRequest());
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication() {
        ListenerPrincipal principal = new ListenerPrincipal(
                LISTENER_ID,
                "Playback Listener",
                null,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                Instant.now());
        return UsernamePasswordAuthenticationToken.authenticated(principal, "session", Set.of());
    }
}
