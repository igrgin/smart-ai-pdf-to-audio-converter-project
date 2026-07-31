package dev.audiobook.platform.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@RequiredArgsConstructor
final class SessionLifecycleFilter extends OncePerRequestFilter {

    static final String LAST_ROTATION = SessionLifecycleFilter.class.getName() + ".lastRotation";

    private final Duration absoluteTimeout;
    private final Duration rotationInterval;
    private final Clock clock;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && clock.millis() - session.getCreationTime() >= absoluteTimeout.toMillis()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (session != null && SecurityContextHolder.getContext().getAuthentication() != null) {
            Object lastRotation = session.getAttribute(LAST_ROTATION);
            long rotationTime = lastRotation instanceof Long value ? value : session.getCreationTime();
            if (clock.millis() - rotationTime >= rotationInterval.toMillis()) {
                request.changeSessionId();
                session.setAttribute(LAST_ROTATION, clock.millis());
            }
        }
        filterChain.doFilter(request, response);
    }
}
