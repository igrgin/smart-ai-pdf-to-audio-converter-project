package dev.audiobook.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.audiobook.platform.PlatformApplication;
import dev.audiobook.platform.entitlement.ConversionEntitlementService;
import dev.audiobook.platform.admission.PublicationSubmissionService;
import dev.audiobook.platform.narration.NarrationSelectionService;
import dev.audiobook.platform.workflow.AudiobookConversionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(classes = PlatformApplication.class)
@Import(IdentitySessionSecurityTest.TestOAuthConfiguration.class)
class IdentitySessionSecurityTest {

    private static final Pattern SCRIPT_NONCE = Pattern.compile("<script nonce=\"([^\"]+)\"");
    private static final UUID LISTENER_ONE = UUID.fromString("01985f42-5f8d-7000-8000-000000000001");
    private static final UUID LISTENER_TWO = UUID.fromString("01985f42-5f8d-7000-8000-000000000002");

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
    void applicationShellUsesThePerResponseNonceAndOnlySameOriginAssets() throws Exception {
        MvcResult first = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn();

        String firstNonce = scriptNonce(first.getResponse().getContentAsString());
        String secondNonce = scriptNonce(second.getResponse().getContentAsString());
        assertThat(first.getResponse().getHeader("Content-Security-Policy"))
                .contains("script-src 'nonce-" + firstNonce + "' 'strict-dynamic'");
        assertThat(first.getResponse().getContentAsString())
                .contains("src=\"/assets/app.js\"", "href=\"/assets/app.css\"")
                .doesNotContain("https://", "http://");
        assertThat(secondNonce).isNotEqualTo(firstNonce);
    }

    @Test
    void anonymousSessionPublishesOnlyCsrfAndStrictPerResponseHeaders() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=()")))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("script-src 'nonce-"),
                        org.hamcrest.Matchers.containsString("'strict-dynamic'"),
                        org.hamcrest.Matchers.containsString("require-trusted-types-for 'script'"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("https:")))))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.listener").doesNotExist())
                .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/auth/session"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(first.getResponse().getHeader("Content-Security-Policy"))
                .isNotEqualTo(second.getResponse().getHeader("Content-Security-Policy"));
        assertThat(first.getResponse().getContentAsString()).doesNotContain("token_type", "id_token", "access_token");
    }

    @Test
    void privateLibraryRequiresAListenerSessionAndNeverExposesListenerIds() throws Exception {
        when(conversionEntitlementService.allowance(LISTENER_ONE))
                .thenReturn(noGrantAllowance());
        when(conversionEntitlementService.allowance(LISTENER_TWO))
                .thenReturn(noGrantAllowance());
        mockMvc.perform(get("/api/v1/library"))
                .andExpect(status().isUnauthorized());

        MvcResult first = mockMvc.perform(get("/api/v1/library").with(authentication(listenerAuthentication(
                        LISTENER_ONE, "First Listener", "first@example.test"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.displayName").value("First Listener"))
                .andExpect(jsonPath("$.audiobooks").isEmpty())
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/library").with(authentication(listenerAuthentication(
                        LISTENER_TWO, "Second Listener", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Second Listener"))
                .andReturn();

        assertThat(first.getResponse().getContentAsString()).doesNotContain(LISTENER_ONE.toString(), LISTENER_TWO.toString());
        assertThat(second.getResponse().getContentAsString()).doesNotContain(LISTENER_ONE.toString(), LISTENER_TWO.toString(), "First Listener");
    }

    @Test
    void privateLibraryExposesAnExplicitNarrationRechoiceWithoutProviderDetails() throws Exception {
        UUID conversionId = UUID.fromString("01985f42-5f8d-7000-8000-000000000028");
        UUID recipeId = UUID.fromString("01985f42-5f8d-7000-8000-000000000128");
        UUID voiceId = UUID.fromString("10000000-0000-7000-8000-000000000001");
        when(conversionEntitlementService.allowance(LISTENER_ONE)).thenReturn(noGrantAllowance());
        when(audiobookConversionService.conversions(LISTENER_ONE)).thenReturn(List.of(
                new AudiobookConversionService.AudiobookConversion(
                        conversionId, AudiobookConversionService.ConversionState.PREPARING)));
        when(narrationSelectionService.narrationChoice(LISTENER_ONE, conversionId)).thenReturn(
                new NarrationSelectionService.NarrationChoiceStatus(
                        1,
                        recipeId,
                        voiceId,
                        "Rowan",
                        NarrationSelectionService.NarrationPace.NATURAL,
                        true));

        MvcResult result = mockMvc.perform(get("/api/v1/library").with(authentication(listenerAuthentication(
                        LISTENER_ONE, "First Listener", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audiobooks[0].conversionId").value(conversionId.toString()))
                .andExpect(jsonPath("$.audiobooks[0].version").value(1))
                .andExpect(jsonPath("$.audiobooks[0].voiceDisplayName").value("Rowan"))
                .andExpect(jsonPath("$.audiobooks[0].pace").value("NATURAL"))
                .andExpect(jsonPath("$.audiobooks[0].explicitNarrationChoiceRequired").value(true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("openai", "modelSnapshot", "providerVoice");
    }

    private static ConversionEntitlementService.Allowance noGrantAllowance() {
        return new ConversionEntitlementService.Allowance(
                ConversionEntitlementService.AllowanceStatus.NO_GRANT,
                0,
                0,
                0,
                0,
                "NO_GRANT");
    }

    @Test
    void linkCeremonyRequiresCsrfExactOriginAndFreshCurrentAuthentication() throws Exception {
        UsernamePasswordAuthenticationToken fresh = listenerAuthentication(
                LISTENER_ONE, "Listener", "listener@example.test", Instant.now().minusSeconds(30));

        mockMvc.perform(post("/api/v1/auth/links/apple")
                        .with(authentication(fresh))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/links/apple")
                        .header("Origin", "https://attacker.example")
                        .with(authentication(fresh))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/links/apple")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(fresh)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/links/apple")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(fresh))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.authorizationPath").value("/oauth2/authorization/apple"));

        mockMvc.perform(post("/api/v1/auth/links/facebook")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(listenerAuthentication(
                                LISTENER_ONE,
                                "Listener",
                                null,
                                Instant.now().minusSeconds(360))))
                        .with(csrf()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.reauthenticationRequired").value(true))
                .andExpect(jsonPath("$.authorizationPath").value("/oauth2/authorization/google"));

        mockMvc.perform(post("/api/v1/auth/links/facebook")
                        .header("Accept", "text/html")
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(listenerAuthentication(
                                LISTENER_ONE,
                                "Listener",
                                null,
                                Instant.now().minusSeconds(360))))
                        .with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @Test
    void brokerAuthorizationUsesCodePkceAndForcesInteractiveAuthentication() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("response_type=code"),
                        org.hamcrest.Matchers.containsString("code_challenge="),
                        org.hamcrest.Matchers.containsString("code_challenge_method=S256"),
                        org.hamcrest.Matchers.containsString("prompt=login"),
                        org.hamcrest.Matchers.containsString("max_age=0"),
                        org.hamcrest.Matchers.containsString("idp=google-idp"))));
    }

    @Test
    void recoveryAndLogoutInvalidateTheApplicationSession() throws Exception {
        MockHttpSession recoverySession = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/recovery")
                        .session(recoverySession)
                        .header("Origin", "http://localhost:3000")
                        .with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("https://login.eu.example/ui/v2/login"));
        assertThat(recoverySession.isInvalid()).isTrue();

        MockHttpSession logoutSession = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .session(logoutSession)
                        .header("Origin", "http://localhost:3000")
                        .with(authentication(listenerAuthentication(LISTENER_ONE, "Listener", null)))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(logoutSession.isInvalid()).isTrue();
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication(UUID listenerId, String name, String email) {
        return listenerAuthentication(listenerId, name, email, Instant.now());
    }

    private static String scriptNonce(String html) {
        Matcher matcher = SCRIPT_NONCE.matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static UsernamePasswordAuthenticationToken listenerAuthentication(
            UUID listenerId, String name, String email, Instant authenticatedAt) {
        ListenerPrincipal principal = new ListenerPrincipal(
                listenerId,
                name,
                email,
                Set.of(SignInProvider.GOOGLE),
                SignInProvider.GOOGLE,
                authenticatedAt);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_LISTENER")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestOAuthConfiguration {

        @Bean
        ClientRegistrationRepository testClientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(
                    registration("google"), registration("apple"), registration("facebook"));
        }

        private static ClientRegistration registration(String id) {
            return ClientRegistration.withRegistrationId(id)
                    .clientId("folio-test")
                    .clientSecret("server-side-test-secret")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://login.eu.example/oauth/v2/authorize")
                    .tokenUri("https://login.eu.example/oauth/v2/token")
                    .userInfoUri("https://login.eu.example/oidc/v1/userinfo")
                    .userNameAttributeName("sub")
                    .jwkSetUri("https://login.eu.example/oauth/v2/keys")
                    .clientName(id)
                    .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                    .build();
        }
    }
}
