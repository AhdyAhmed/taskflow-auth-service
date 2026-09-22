package com.ahdyahmed.taskflow.repository;

import com.ahdyahmed.taskflow.domain.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Day 13: backs the refresh-token invalidation the roadmap explicitly
     * calls for on password reset. A bulk {@code UPDATE}, not
     * "load every row, set revoked, save each" — a user could plausibly
     * have several live refresh tokens (multiple devices/browsers), and
     * there's no reason to pull them into memory one at a time just to
     * flip one column on each. {@code @Modifying} is required on any
     * write query.
     * <p>
     * Both {@code flushAutomatically} and {@code clearAutomatically} are
     * deliberate, and dropping either one breaks {@code AuthService.resetPassword()}
     * silently rather than loudly. That method calls this bulk update
     * right after setting the new password hash and marking the reset
     * token used — both still just pending, unflushed changes on managed
     * entities at that point, not yet written to the DB. A JPQL bulk
     * {@code UPDATE} bypasses the persistence context entirely and does
     * NOT auto-flush pending entity state first the way a `SELECT` would
     * — {@code flushAutomatically = true} is what forces those two
     * pending writes out before this query runs, so they don't get lost.
     * {@code clearAutomatically = true} then detaches the persistence
     * context afterward, so nothing later in the same transaction can
     * read a stale, pre-revocation view of a token this call just
     * revoked. Skip the flush and the password/token-used changes
     * silently vanish; skip the clear and a subsequent read in the same
     * transaction could see stale data. Needed together, for different
     * reasons.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken rt set rt.revoked = true where rt.user.id = :userId and rt.revoked = false")
    void revokeAllForUser(@Param("userId") Long userId);
}
