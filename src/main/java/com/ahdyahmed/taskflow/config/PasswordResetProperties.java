package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 13, same rationale as {@link VerificationProperties}. Deliberately
 * a much shorter default than verification's 24 hours — see
 * {@code application.yml} for why.
 */
@ConfigurationProperties(prefix = "app.security.password-reset")
public record PasswordResetProperties(long tokenExpirationHours) {
}
