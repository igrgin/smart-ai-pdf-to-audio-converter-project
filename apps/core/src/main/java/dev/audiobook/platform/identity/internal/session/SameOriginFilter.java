package dev.audiobook.platform.identity.internal.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SameOriginFilter extends OncePerRequestFilter {

    private static final String STRIPE_EVENT_PATH = "/api/v1/integrations/stripe/events";

    private static final Set<String> SAFE_METHODS = Set.of(
            HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name(), HttpMethod.TRACE.name());

    private final String allowedOrigin;

    public SameOriginFilter(URI allowedOrigin) {
        this.allowedOrigin = originOf(allowedOrigin);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")
                && !STRIPE_EVENT_PATH.equals(request.getRequestURI())
                && !SAFE_METHODS.contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            if (!allowedOrigin.equals(origin)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String originOf(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
    }
}
