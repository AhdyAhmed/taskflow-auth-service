package com.ahdyahmed.taskflow.config;

import com.ahdyahmed.taskflow.security.JwtAuthenticationFilter;
import com.ahdyahmed.taskflow.security.RateLimitingFilter;
import com.ahdyahmed.taskflow.security.RestAccessDeniedHandler;
import com.ahdyahmed.taskflow.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Day 7: this is where {@code permitAll()} finally goes away. Only the
 * four endpoints that have to be reachable without a token stay open —
 * you can't require a login to log in. Everything else now requires a
 * valid access token at minimum (role-specific rules live as
 * {@code @PreAuthorize} on service methods, enabled by
 * {@link MethodSecurityConfig}, not here).
 * <p>
 * {@link RestAuthenticationEntryPoint} and {@link RestAccessDeniedHandler}
 * are wired in so 401s (no/invalid token) and 403s (valid token, wrong
 * role) come back in the same JSON shape as every other API error —
 * without them, Spring Security's own default handlers would produce a
 * plain-text or differently-shaped body for exactly these two cases.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_AUTH_ENDPOINTS = {
            "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout",
            // Day 12: both reachable by someone who, by definition, isn't
            // verified/logged-in yet — they have to stay open.
            "/auth/verify", "/auth/resend-verification",
            // Day 13: same reasoning — a locked-out user resetting their
            // password can't have a valid access token to present.
            "/auth/forgot-password", "/auth/reset-password"
    };

    // Day 17: the docs themselves have to be reachable before anyone has
    // a token to try them with — same principle as PUBLIC_AUTH_ENDPOINTS
    // above, just for tooling instead of the auth flow itself. Kept as a
    // separate array (rather than folded into PUBLIC_AUTH_ENDPOINTS)
    // since these aren't auth endpoints at all, and conflating "public
    // because you can't be logged in yet" with "public because it's
    // documentation" would muddy why each entry is here.
    private static final String[] PUBLIC_DOC_ENDPOINTS = {
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .requestMatchers(PUBLIC_DOC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                // Day 11: rate limiting runs first, ahead of JWT parsing —
                // a request that's going to be rejected as 429 shouldn't
                // pay for token parsing or touch the SecurityContext at all.
                // Order matters here: a filter can only be used as an anchor
                // (the 2nd argument) once it has been registered itself.
                // So JWT is registered first, relative to a built-in Spring
                // filter, and only then is the rate limiter placed before it.
                // Resulting chain: RateLimitingFilter -> JwtAuthenticationFilter
                // -> UsernamePasswordAuthenticationFilter.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Delegates to Spring's own auto-configured manager, which wires up a
     * {@code DaoAuthenticationProvider} from the single
     * {@code CustomUserDetailsService} + {@code PasswordEncoder} beans
     * already in context. Used by {@code AuthService.login()} — and
     * because that provider checks {@code UserDetails.isEnabled()}/
     * {@code isAccountNonLocked()} before it even compares passwords,
     * login respects account lockout (Day 10) and will respect email
     * verification (Day 12) the moment that flag is wired up too, with
     * zero further changes to the login code itself.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
