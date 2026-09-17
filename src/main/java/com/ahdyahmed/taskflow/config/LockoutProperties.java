package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 10, same rationale as {@link JwtProperties}: one immutable record
 * instead of scattered {@code @Value} injections, and a startup-time
 * failure if `app.security.lockout.*` is missing rather than a silent
 * wrong default the first time someone gets locked out.
 */
@ConfigurationProperties(prefix = "app.security.lockout")
public record LockoutProperties(
        int maxFailedAttempts,
        long lockoutDurationMinutes
) {
}
