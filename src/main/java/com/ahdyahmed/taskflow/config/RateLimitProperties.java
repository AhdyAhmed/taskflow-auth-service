package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 11, same rationale as {@link JwtProperties}/{@link LockoutProperties}.
 * {@code capacity} tokens are available in each window, refilled
 * gradually (not all at once) over {@code refillDurationSeconds} — see
 * {@code RateLimitingFilter} for how these turn into an actual bucket.
 */
@ConfigurationProperties(prefix = "app.security.rate-limit")
public record RateLimitProperties(
        int capacity,
        long refillDurationSeconds
) {
}
