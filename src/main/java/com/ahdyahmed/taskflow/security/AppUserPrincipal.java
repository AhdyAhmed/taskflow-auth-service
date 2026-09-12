package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.domain.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Wraps the domain {@link User} instead of building a generic Spring
 * Security {@code User}. Ownership checks landing on Day 7-9 can call
 * {@link #getUser()} straight off the authenticated principal and get
 * the real id/email/role, instead of re-querying {@code UserRepository}
 * by username inside every {@code @PreAuthorize} expression.
 */
@Getter
public class AppUserPrincipal implements UserDetails {

    private final User user;

    public AppUserPrincipal(User user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonLocked() {
        // Real lockout logic (failedLoginAttempts / lockedUntil) is wired
        // up on Day 10-11; this always returns true until then.
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }
}
