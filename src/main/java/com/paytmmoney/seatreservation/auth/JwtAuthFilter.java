package com.paytmmoney.seatreservation.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Verifies the Authorization header (if present) and stashes the token-derived user id as a
 * request attribute. Does NOT itself reject unauthenticated requests - endpoints that require a
 * caller identity ask for it via {@link AuthUser} and get a 401 if it's absent, so public
 * endpoints (health, show creation/reads, token issuance) stay untouched by this filter.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = "authUserId";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<String> userId = jwtService.verifyAndGetUserId(token);
            userId.ifPresent(id -> request.setAttribute(ATTRIBUTE, id));
        }
        chain.doFilter(request, response);
    }
}
