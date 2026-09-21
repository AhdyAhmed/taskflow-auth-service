package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Day 12, same rationale as {@link LockoutProperties}/{@link RateLimitProperties}. */
@ConfigurationProperties(prefix = "app.security.verification")
public record VerificationProperties(long tokenExpirationHours) {
}
