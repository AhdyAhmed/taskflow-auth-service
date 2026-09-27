package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.config.LockoutProperties;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Day 16 ("fill gaps + coverage review"). This is the specific gap Day
 * 14's README flagged: {@code AuthServiceTest} only verifies that
 * {@code AuthService} *calls* {@link LoginAttemptService} correctly —
 * it never exercised the counting/locking arithmetic itself. This class
 * does, entirely through the {@code User} entity's own state after each
 * call (no Mockito verification needed beyond that — the method's whole
 * contract is "mutate this user correctly").
 * <p>
 * Real {@link LockoutProperties} values matching {@code application.yml}
 * (5 attempts, 15-minute lockout) are used directly rather than mocked,
 * since a record with two primitives is simpler to construct than to mock.
 */
@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MINUTES = 15;

    @Mock
    private UserRepository userRepository;

    private LoginAttemptService loginAttemptService;
    private User user;

    @BeforeEach
    void setUp() {
        LockoutProperties lockoutProperties =
                new LockoutProperties(MAX_FAILED_ATTEMPTS, LOCKOUT_DURATION_MINUTES);
        loginAttemptService = new LoginAttemptService(userRepository, lockoutProperties);

        user = User.builder()
                .id(1L)
                .email("target@taskflow.test")
                .passwordHash("bcrypt-hash")
                .role(Role.USER)
                .enabled(true)
                .failedLoginAttempts(0)
                .lockedUntil(null)
                .build();
    }

    @Test
    void registerFailedAttempt_firstFailure_incrementsToOneAndDoesNotLock() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        loginAttemptService.registerFailedAttempt(user.getEmail());

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_belowThreshold_incrementsWithoutLocking() {
        user.setFailedLoginAttempts(3);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        loginAttemptService.registerFailedAttempt(user.getEmail());

        assertThat(user.getFailedLoginAttempts()).isEqualTo(4);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_reachingMaxAttempts_locksTheAccount() {
        // Four prior failures; this call is the fifth — the one that
        // crosses app.security.lockout.max-failed-attempts (5).
        user.setFailedLoginAttempts(MAX_FAILED_ATTEMPTS - 1);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        LocalDateTime before = LocalDateTime.now();
        loginAttemptService.registerFailedAttempt(user.getEmail());
        LocalDateTime after = LocalDateTime.now();

        assertThat(user.getFailedLoginAttempts()).isEqualTo(MAX_FAILED_ATTEMPTS);
        assertThat(user.getLockedUntil()).isNotNull();
        // Should be ~15 minutes out from "now" at call time — bounded by
        // the before/after window rather than pinned to one instant, to
        // avoid a flaky test over an exact-millisecond comparison.
        assertThat(user.getLockedUntil()).isAfter(before.plusMinutes(LOCKOUT_DURATION_MINUTES).minusSeconds(1));
        assertThat(user.getLockedUntil()).isBefore(after.plusMinutes(LOCKOUT_DURATION_MINUTES).plusSeconds(1));
    }

    @Test
    void registerFailedAttempt_accountAlreadyLocked_doesNotIncrementOrExtendLockout() {
        LocalDateTime originalLockExpiry = LocalDateTime.now().plusMinutes(10);
        user.setFailedLoginAttempts(MAX_FAILED_ATTEMPTS);
        user.setLockedUntil(originalLockExpiry);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        loginAttemptService.registerFailedAttempt(user.getEmail());

        // Hammering a locked account changes nothing: not the count, and
        // — the specific behavior worth pinning down — not the lock's
        // expiry either. A locked-out attacker retrying repeatedly
        // shouldn't be able to keep the lock alive longer than the
        // original window by continuing to guess.
        assertThat(user.getFailedLoginAttempts()).isEqualTo(MAX_FAILED_ATTEMPTS);
        assertThat(user.getLockedUntil()).isEqualTo(originalLockExpiry);
    }

    @Test
    void registerFailedAttempt_lockHasAlreadyExpired_treatsThisFailureAsAFreshStart() {
        // A lock that's in the past: naturally expired, not yet cleared by
        // a successful login. The next failure should start counting from
        // 1 again, not from wherever the old count left off.
        user.setFailedLoginAttempts(MAX_FAILED_ATTEMPTS);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        loginAttemptService.registerFailedAttempt(user.getEmail());

        assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void registerFailedAttempt_unknownEmail_doesNothingAndDoesNotThrow() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        loginAttemptService.registerFailedAttempt("nobody@taskflow.test");

        // Nothing to assert on a User that was never loaded — the
        // contract here is simply "no exception, no side effect."
    }

    @Test
    void resetFailedAttempts_clearsCountAndAnyActiveLock() {
        user.setFailedLoginAttempts(MAX_FAILED_ATTEMPTS);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        loginAttemptService.resetFailedAttempts(user.getId());

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void resetFailedAttempts_unknownUserId_doesNothingAndDoesNotThrow() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        loginAttemptService.resetFailedAttempts(999L);
    }
}
