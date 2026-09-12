package com.ahdyahmed.taskflow.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Deliberately permissive on failure: a missing, malformed, expired, or
 * non-access (e.g. refresh) token just means the request proceeds
 * unauthenticated — it's up to the authorization rules downstream (still
 * {@code permitAll()} everywhere as of Day 5; RBAC lands Day 7-9) to
 * decide whether that's acceptable. This filter's only job is "if a
 * usable access token is present, populate the SecurityContext" — never
 * to reject the request itself.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());

            if (jwtService.isValid(token)
                    && jwtService.isAccessToken(token)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {

                authenticate(request, jwtService.extractEmail(token));
            }
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String email) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
            return;
        }

        var authToken = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }
}
