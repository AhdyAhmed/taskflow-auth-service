package com.ahdyahmed.taskflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * {@code @PreAuthorize} support (Day 7), plus a role hierarchy so ADMIN
 * implicitly has everything MANAGER has, and MANAGER implicitly has
 * everything USER has. Without this, every {@code @PreAuthorize} would
 * need {@code hasAnyRole('MANAGER','ADMIN')} instead of just
 * {@code hasRole('MANAGER')} — the hierarchy is what lets service methods
 * state the *minimum* role required and have it compose correctly.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("MANAGER")
                .role("MANAGER").implies("USER")
                .build();
    }

    /**
     * Deliberately a {@code static} bean method — this is the pattern
     * documented in Spring Security's own reference docs. Method security
     * infrastructure initializes very early, before normal
     * {@code @Configuration} classes are fully processed; a non-static
     * factory method here can end up instantiated too early to see the
     * {@link RoleHierarchy} bean correctly.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
    }
}
