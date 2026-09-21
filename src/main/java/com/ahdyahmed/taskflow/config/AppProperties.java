package com.ahdyahmed.taskflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Day 12. Just one property so far — {@code baseUrl}, the origin used to
 * build absolute links in emails (verification today, password reset on
 * Day 13). Living at the top-level {@code app.*} prefix rather than
 * nested under {@code app.security.*}: it isn't a security control like
 * the lockout/rate-limit/verification-expiry settings it sits next to in
 * {@code application.yml}, it's just app-wide config that happens to be
 * needed here first.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl) {
}
