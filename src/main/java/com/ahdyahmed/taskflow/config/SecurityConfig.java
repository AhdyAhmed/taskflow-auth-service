package com.ahdyahmed.taskflow.config;

import com.ahdyahmed.taskflow.security.JwtAuthenticationFilter;
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
            "/auth/register", "/auth/login", "/auth/refresh", "/auth/logout"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_AUTH_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

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
