package dev.audiobook.platform.identity.websecurity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String NONCE_ATTRIBUTE = SecurityHeadersFilter.class.getName() + ".nonce";
    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        byte[] nonceBytes = new byte[18];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        response.setHeader("Content-Security-Policy", csp(nonce));
        response.setHeader(
                "Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("X-Frame-Options", "DENY");
        filterChain.doFilter(request, response);
    }

    private static String csp(String nonce) {
        return "default-src 'none'; script-src 'nonce-"
                + nonce
                + "' 'strict-dynamic'; "
                + "style-src 'self'; font-src 'self'; img-src 'self' data:; media-src 'self'; "
                + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; object-src 'none'; "
                + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "
                + "require-trusted-types-for 'script'; trusted-types folio";
    }
}
