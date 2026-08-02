package dev.audiobook.platform.bootstrap.internal;

import dev.audiobook.platform.identity.internal.IdentitySecurityProperties;
import dev.audiobook.platform.identity.internal.signin.BrokerAuthorizationRequestResolver;
import dev.audiobook.platform.identity.internal.signin.BrokerIdentity;
import dev.audiobook.platform.identity.internal.signin.BrokerOidcUserService;
import dev.audiobook.platform.identity.internal.signin.BrokerOidcUserServiceImpl;
import dev.audiobook.platform.identity.internal.signin.OidcLoginSuccessHandler;
import dev.audiobook.platform.identity.internal.listener.ListenerIdentityService;
import dev.audiobook.platform.identity.internal.websecurity.SameOriginFilter;
import dev.audiobook.platform.identity.internal.websecurity.SecurityHeadersFilter;
import dev.audiobook.platform.identity.internal.session.SessionLifecycleFilter;

import dev.audiobook.platform.admission.internal.inspection.intake.InspectionWorkDeliveryController;
import dev.audiobook.platform.entitlement.internal.subscription.stripe.StripeDemonstrationSubscriptionWebhookController;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ClientProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlatformSecurityConfiguration {

    @Bean
    DefaultCookieSerializer sessionCookieSerializer(IdentitySecurityProperties properties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("FOLIO_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.secureSessionCookie());
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties) {
        Map<String, ClientRegistration> configured = new OAuth2ClientPropertiesMapper(properties).asClientRegistrations();
        return new InMemoryClientRegistrationRepository(configured.values().stream()
                .map(registration -> ClientRegistration.withClientRegistration(registration)
                        .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                        .build())
                .toList());
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    BrokerIdentity brokerIdentity(IdentitySecurityProperties properties, Clock identityClock) {
        return new BrokerIdentity(
                properties.brokerIssuer(), properties.freshAuthenticationMaxAge(), identityClock);
    }

    @Bean
    BrokerOidcUserService brokerOidcUserService(BrokerIdentity brokerIdentity) {
        return new BrokerOidcUserServiceImpl(brokerIdentity);
    }

    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            IdentitySecurityProperties properties) {
        return new BrokerAuthorizationRequestResolver(clientRegistrationRepository, properties);
    }

    @Bean
    OidcLoginSuccessHandler oidcLoginSuccessHandler(
            ListenerIdentityService listenerIdentityService,
            BrokerIdentity brokerIdentity,
            IdentitySecurityProperties securityProperties,
            SecurityContextRepository securityContextRepository,
            Clock identityClock) {
        return new OidcLoginSuccessHandler(
                listenerIdentityService,
                brokerIdentity,
                securityProperties,
                securityContextRepository,
                identityClock);
    }

    @Bean
    SameOriginFilter sameOriginFilter(IdentitySecurityProperties properties) {
        return new SameOriginFilter(properties.allowedOrigin());
    }

    @Bean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    SessionLifecycleFilter sessionLifecycleFilter(IdentitySecurityProperties properties, Clock identityClock) {
        return new SessionLifecycleFilter(
                properties.sessionAbsoluteTimeout(), properties.sessionRotationInterval(), identityClock);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            BrokerOidcUserService brokerOidcUserService,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            OidcLoginSuccessHandler successHandler,
            SameOriginFilter sameOriginFilter,
            SecurityHeadersFilter securityHeadersFilter,
            SessionLifecycleFilter sessionLifecycleFilter) throws Exception {
        var uploadCapability = PathPatternRequestMatcher.withDefaults().matcher(
                org.springframework.http.HttpMethod.PUT,
                "/api/v1/publication-submissions/{submissionId}/upload");
        var inspectionDelivery = PathPatternRequestMatcher.withDefaults().matcher(
                org.springframework.http.HttpMethod.POST,
                InspectionWorkDeliveryController.DELIVERY_PATH);
        var stripeEvents = PathPatternRequestMatcher.withDefaults().matcher(
                org.springframework.http.HttpMethod.POST,
                StripeDemonstrationSubscriptionWebhookController.EVENT_PATH);
        http
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/api/v1/platform/status",
                                "/api/v1/auth/session",
                                "/api/v1/auth/providers",
                                "/api/v1/auth/recovery",
                                "/oauth2/authorization/**",
                                "/login/oauth2/code/**",
                                "/actuator/health/**")
                        .permitAll()
                        .requestMatchers(uploadCapability).permitAll()
                        .requestMatchers(inspectionDelivery).permitAll()
                        .requestMatchers(stripeEvents).permitAll()
                        .requestMatchers("/api/v1/operator/action-queue/**").hasAnyRole(
                                "SUPPORT", "RELIABILITY", "ENTITLEMENT", "VOICE",
                                "INCIDENT_RESPONDER", "SECURITY_REVIEWER")
                        .requestMatchers("/api/v1/operator/**").hasRole("OPERATOR")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .csrf(csrf -> csrf.ignoringRequestMatchers(uploadCapability, inspectionDelivery, stripeEvents))
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(brokerOidcUserService))
                        .authorizedClientRepository(authorizedClientRepository)
                        .successHandler(successHandler)
                        .failureHandler((request, response, exception) -> {
                            if (request.getSession(false) != null) {
                                request.getSession(false).invalidate();
                            }
                            response.sendRedirect("/?sign-in=failed");
                        }))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(
                                org.springframework.http.HttpMethod.POST, "/api/v1/auth/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("FOLIO_SESSION")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            String accept = request.getHeader("Accept");
                            if (accept != null && accept.contains("text/html")) {
                                response.sendRedirect("/");
                            } else {
                                response.setStatus(HttpStatus.NO_CONTENT.value());
                            }
                        }))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(Customizer.withDefaults()))
                .addFilterBefore(sameOriginFilter, CsrfFilter.class)
                .addFilterAfter(sessionLifecycleFilter, CsrfFilter.class)
                .addFilterAfter(securityHeadersFilter, HeaderWriterFilter.class);
        return http.build();
    }
}
