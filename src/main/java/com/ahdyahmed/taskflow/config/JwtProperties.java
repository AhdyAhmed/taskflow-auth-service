package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A record here (not scattered {@code @Value} injections) so every JWT
 * setting is declared once, is immutable, and fails fast at startup if
 * `app.jwt.secret` is missing — instead of surfacing as an NPE the first
 * time a token is signed.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpirationMs,
        long refreshTokenExpirationMs
) {
}
