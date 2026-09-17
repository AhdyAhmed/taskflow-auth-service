package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.config.LockoutProperties;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Day 10. This is a separate {@code @Service}, not a couple of private
 * methods on {@link AuthService}, and that's load-bearing rather than
 * stylistic.
 * <p>
 * {@code registerFailedAttempt} has to persist even though the login
 * attempt that triggered it is about to fail — {@code AuthService.login()}
 * throws {@code InvalidCredentialsException} right after calling it, and
 * Spring's default rollback-on-unchecked-exception behavior would erase
 * that write along with everything else in the same transaction. Giving
 * it {@code @Transactional(propagation = REQUIRES_NEW)} commits it in
 * its own physical transaction before the caller's exception gets a
 * chance to roll anything back — but {@code REQUIRES_NEW} (like every
 * {@code @Transactional} variant) only takes effect on a call that goes
 * through the Spring proxy for the bean. A private method called as
 * {@code this.registerFailedAttempt(...)} from inside {@code AuthService}
 * bypasses that proxy entirely and the annotation would be silently
 * ignored — the classic Spring AOP self-invocation gap. Putting this
 * logic on its own bean, called as a normal collaborator from
 * {@code AuthService}, is what makes {@code REQUIRES_NEW} real instead
 * of a no-op.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final UserRepository userRepository;
    private final LockoutProperties lockoutProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedAttempt(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            if (isCurrentlyLocked(user)) {
                // Already locked — don't pile more failures on top of the
                // count, and don't extend the existing lockout window.
                // The account stays locked for the time already set;
                // repeatedly hammering a locked account doesn't make the
                // lockout longer.
                return;
            }

            // A stale, already-expired lock counts as a fresh start: this
            // failure becomes attempt 1 of a new window, not attempt 6 of
            // the old one. Without this, one failed login any time after
            // a lockout naturally expires would immediately re-lock the
            // account, instead of the account getting a genuinely clean
            // slate of maxFailedAttempts tries.
            boolean lockJustExpired = user.getLockedUntil() != null;
            int attempts = lockJustExpired ? 1 : user.getFailedLoginAttempts() + 1;

            user.setFailedLoginAttempts(attempts);
            user.setLockedUntil(null);
            if (attempts >= lockoutProperties.maxFailedAttempts()) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutProperties.lockoutDurationMinutes()));
            }
        });
    }

    /**
     * Runs inside the caller's existing transaction (default propagation)
     * — unlike {@link #registerFailedAttempt}, this only ever runs on the
     * success path, where there's no pending rollback to survive.
     */
    @Transactional
    public void resetFailedAttempts(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
        });
    }

    private boolean isCurrentlyLocked(User user) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now());
    }
}
