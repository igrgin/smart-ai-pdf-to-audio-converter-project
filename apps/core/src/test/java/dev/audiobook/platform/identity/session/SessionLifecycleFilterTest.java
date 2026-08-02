package dev.audiobook.platform.identity.session;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

class SessionLifecycleFilterTest {

    private static final Instant NOW = Instant.parse("2026-07-31T20:00:00Z");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void absoluteExpiryInvalidatesTheServerSideSession() throws Exception {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getCreationTime()).thenReturn(NOW.minus(Duration.ofHours(8)).toEpochMilli());
        SessionLifecycleFilter filter = filter();

        filter.doFilterInternal(request, response, chain);

        verify(session).invalidate();
        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void authenticatedSessionIdRotatesWithoutExtendingAbsoluteLifetime() throws Exception {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getCreationTime()).thenReturn(NOW.minus(Duration.ofHours(1)).toEpochMilli());
        when(session.getAttribute(SessionLifecycleFilter.LAST_ROTATION))
                .thenReturn(NOW.minus(Duration.ofMinutes(11)).toEpochMilli());
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                "listener", null, java.util.List.of()));

        filter().doFilterInternal(request, response, chain);

        verify(request).changeSessionId();
        verify(session).setAttribute(SessionLifecycleFilter.LAST_ROTATION, NOW.toEpochMilli());
        verify(chain).doFilter(request, response);
    }

    private static SessionLifecycleFilter filter() {
        return new SessionLifecycleFilter(
                Duration.ofHours(8), Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
