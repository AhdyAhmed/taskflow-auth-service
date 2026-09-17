package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.domain.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
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

    /**
     * Day 10: real lockout logic, wired to {@code User.lockedUntil}. This
     * runs as part of Spring Security's {@code PreAuthenticationChecks} —
     * before the password is even compared — so a locked account is
     * rejected on username alone, correct password or not. The account
     * unlocks itself the instant {@code lockedUntil} passes; nothing has
     * to actively clear the field for a login to succeed again (though
     * {@code LoginAttemptService} does clean it up on the next successful
     * login, so the row doesn't carry a stale past timestamp forever).
     */
    @Override
    public boolean isAccountNonLocked() {
        LocalDateTime lockedUntil = user.getLockedUntil();
        return lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now());
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }
}
