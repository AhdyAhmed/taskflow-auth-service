package com.ahdyahmed.taskflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Kept as its own config class (rather than annotating the main
 * application class) so auditing setup is easy to find and easy to
 * extend later (e.g. an AuditorAware bean once "createdBy" needs to
 * be the currently authenticated user instead of set manually).
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
