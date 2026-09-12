package com.ahdyahmed.taskflow.config;

import com.ahdyahmed.taskflow.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Replaces {@code TemporaryOpenSecurityConfig} (Day 1-4) outright, as
 * promised — not extended, a clean swap.
 * <p>
 * What actually changes today: the app now runs statelessly (no HTTP
 * session, so CSRF protection — which only matters for cookie-based
 * sessions — stays disabled for the same reason it always was), and
 * {@link JwtAuthenticationFilter} is wired into the chain ahead of
 * Spring's own {@link UsernamePasswordAuthenticationFilter}, so a valid
 * Bearer access token, if one is sent, populates the SecurityContext.
 * <p>
 * What does NOT change today: every endpoint is still {@code permitAll()}.
 * There's no login endpoint yet to obtain a token through the API (Day 6),
 * and role/ownership-based access rules don't land until Day 7-9. Today
 * is purely "the JWT machinery works", not "the JWT machinery is enforced".
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
