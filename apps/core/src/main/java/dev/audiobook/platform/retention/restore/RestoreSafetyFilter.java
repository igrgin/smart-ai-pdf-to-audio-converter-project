package dev.audiobook.platform.retention.restore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RestoreSafetyFilter extends OncePerRequestFilter {

    private final RestoreSafetyGate restoreSafetyGate;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean privateTraffic =
                (path.startsWith("/api/v1/") && !path.equals("/api/v1/platform/status"))
                        || path.startsWith("/oauth2/authorization/")
                        || path.startsWith("/login/oauth2/code/");
        if (!restoreSafetyGate.isSafe() && privateTraffic) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
