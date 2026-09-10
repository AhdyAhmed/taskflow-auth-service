package com.ahdyahmed.taskflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORARY — replaced, not extended, once real auth lands.
 * <p>
 * spring-boot-starter-security has been on the classpath since Day 1, and
 * without any {@link SecurityFilterChain} bean, Spring Boot's
 * auto-configuration would secure every endpoint behind HTTP Basic with a
 * randomly generated password logged at startup. That's the right default
 * for a real app, but it would make the CRUD endpoints built on Day 3
 * impossible to exercise before JWT auth exists (Day 4-6) and RBAC
 * (Day 7-9). This bean explicitly opens everything up in the meantime.
 * <p>
 * CSRF is disabled because this is a stateless JSON API with no cookie-based
 * session — CSRF protection is a browser-session concern that doesn't apply
 * here, and it stays disabled once JWT auth lands too, for the same reason.
 */
@Configuration
@EnableWebSecurity
public class TemporaryOpenSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
