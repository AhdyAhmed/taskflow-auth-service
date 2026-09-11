package com.ahdyahmed.taskflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Deliberately its own config class, separate from
 * {@link TemporaryOpenSecurityConfig}. That class gets thrown away wholesale
 * once real JWT security lands (Day 4-6 is done, this is Day 4's slice of
 * it); this bean doesn't — it's needed by registration today and by login's
 * credential check on Day 6, so it shouldn't live inside something with
 * "Temporary" in the name.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
