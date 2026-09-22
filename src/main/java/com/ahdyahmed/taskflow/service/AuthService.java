package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.config.AppProperties;
import com.ahdyahmed.taskflow.config.JwtProperties;
import com.ahdyahmed.taskflow.config.PasswordResetProperties;
import com.ahdyahmed.taskflow.config.VerificationProperties;
import com.ahdyahmed.taskflow.domain.entity.PasswordResetToken;
import com.ahdyahmed.taskflow.domain.entity.RefreshToken;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.entity.VerificationToken;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.EmailRequest;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.RefreshRequest;
import com.ahdyahmed.taskflow.dto.request.RegisterRequest;
import com.ahdyahmed.taskflow.dto.request.ResetPasswordRequest;
import com.ahdyahmed.taskflow.dto.response.AuthResponse;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.email.EmailService;
import com.ahdyahmed.taskflow.exception.AccountNotVerifiedException;
import com.ahdyahmed.taskflow.exception.EmailAlreadyInUseException;
import com.ahdyahmed.taskflow.exception.InvalidCredentialsException;
import com.ahdyahmed.taskflow.exception.InvalidTokenException;
import com.ahdyahmed.taskflow.mapper.UserMapper;
import com.ahdyahmed.taskflow.repository.PasswordResetTokenRepository;
import com.ahdyahmed.taskflow.repository.RefreshTokenRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import com.ahdyahmed.taskflow.repository.VerificationTokenRepository;
import com.ahdyahmed.taskflow.security.AppUserPrincipal;
import com.ahdyahmed.taskflow.security.JwtService;
import com.ahdyahmed.taskflow.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final VerificationProperties verificationProperties;
    private final PasswordResetProperties passwordResetProperties;
    private final AppProperties appProperties;
    private final UserMapper userMapper;
    private final LoginAttemptService loginAttemptService;
    private final EmailService emailService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // Confirming the email is already taken is normal signup UX (unlike
        // login below, which deliberately avoids revealing which half of a
        // credential pair was wrong).
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyInUseException("An account with this email already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.USER)
                // Day 12: starts disabled. isEnabled() on the UserDetails
                // this becomes is what makes login() below refuse an
                // unverified account, via Spring Security's own
                // PreAuthenticationChecks — nothing in login() had to
                // change to make that true, per the Day 6 design note on
                // AuthenticationManager.
                .enabled(false)
                .build();
        user = userRepository.save(user);

        issueVerificationToken(user);

        return userMapper.toResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        AppUserPrincipal principal;
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
            principal = (AppUserPrincipal) authentication.getPrincipal();
        } catch (DisabledException ex) {
            // Day 12: unlike a wrong password or a locked account (below),
            // "this account exists but hasn't verified its email" isn't
            // attack-relevant information worth hiding behind a generic
            // message — register() already confirms an email is taken
            // (Day 9's documented trade-off), and telling someone with the
            // *correct* password why login still isn't working is
            // legitimate UX, not a new enumeration leak. Deliberately NOT
            // routed through registerFailedAttempt: this check runs before
            // the password is even compared (same PreAuthenticationChecks
            // ordering as lockout), so it fires on a correct password too —
            // that's not a sign of credential guessing, so it shouldn't
            // count toward one.
            throw new AccountNotVerifiedException("Please verify your email before logging in");
        } catch (AuthenticationException ex) {
            // Deliberately the same message whether the email doesn't
            // exist, the password is wrong, or (Day 10) the account is
            // locked — anything more specific here is a user enumeration
            // leak. registerFailedAttempt runs in its own transaction
            // (see LoginAttemptService) precisely so it survives the
            // rollback this throw is about to trigger.
            loginAttemptService.registerFailedAttempt(request.getEmail());
            throw new InvalidCredentialsException("Invalid email or password");
        }

        loginAttemptService.resetFailedAttempts(principal.getUser().getId());
        return issueTokenPair(principal.getUser());
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String presentedToken = request.getRefreshToken();

        if (!jwtService.isValid(presentedToken) || !jwtService.isRefreshToken(presentedToken)) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(presentedToken))
                .filter(rt -> !rt.isRevoked())
                .filter(rt -> rt.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new InvalidTokenException("Refresh token is invalid or expired"));

        // Rotate: the presented token is single-use. Revoking it here means
        // a stolen-and-replayed refresh token fails the moment the real
        // owner refreshes first, instead of staying valid for its whole
        // 7-day lifetime.
        stored.setRevoked(true);

        return issueTokenPair(stored.getUser());
    }

    @Transactional
    public void logout(RefreshRequest request) {
        // Idempotent on purpose: an unknown, already-revoked, or malformed
        // token still results in "logged out" from the caller's point of
        // view — there's nothing to be learned by responding differently
        // for those cases, and a client retrying a logout call shouldn't
        // see an error.
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(request.getRefreshToken()))
                .ifPresent(rt -> rt.setRevoked(true));
    }

    /**
     * Day 12: {@code GET /auth/verify?token=...}. A not-found, expired, or
     * already-used token all fail identically (same exception, same
     * message) — there's no legitimate reason for a caller to be able to
     * tell those three states apart from the response alone.
     */
    @Transactional
    public UserResponse verify(String rawToken) {
        VerificationToken stored = verificationTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken))
                .filter(vt -> !vt.isUsed())
                .filter(vt -> vt.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new InvalidTokenException("Verification token is invalid or expired"));

        stored.setUsed(true);
        User user = stored.getUser();
        user.setEnabled(true);

        return userMapper.toResponse(user);
    }

    /**
     * Day 12: {@code POST /auth/resend-verification}. Always behaves the
     * same from the caller's point of view — 204, no body — whether the
     * email doesn't exist, belongs to an already-verified account, or a
     * genuinely new token just got issued. That's the opposite choice
     * from {@link #register}'s duplicate-email 409, and deliberately so:
     * register has a real UX need to confirm a duplicate immediately, so
     * the person doesn't lose their in-progress signup form; resend has
     * no equivalent need, and turning it into a second existence-check
     * oracle (an unlimited one, unlike the rate-limited but individually
     * meaningful signal register gives) buys nothing back for it.
     */
    @Transactional
    public void resendVerification(EmailRequest request) {
        userRepository.findByEmail(request.getEmail())
                .filter(user -> !user.isEnabled())
                .ifPresent(this::issueVerificationToken);
    }

    /**
     * Day 13: {@code POST /auth/forgot-password}. Always {@code 204},
     * always identical, whether the email exists or not — this is the
     * single most standard version of this exact trade-off across real
     * auth systems (unlike Day 9's deliberately-honest register 409, or
     * Day 12's resend-verification, forgot-password revealing account
     * existence is a textbook enumeration vector with essentially no
     * counterbalancing UX need, since "check your email" is a perfectly
     * good response whether or not there's actually an email coming).
     */
    @Transactional
    public void forgotPassword(EmailRequest request) {
        userRepository.findByEmail(request.getEmail())
                .ifPresent(this::issuePasswordResetToken);
    }

    /**
     * Day 13: {@code POST /auth/reset-password}. Three things happen
     * together, in one transaction: the password changes, the token that
     * authorized it is consumed so it can't be replayed, and — the
     * roadmap's explicit requirement — every refresh token this user
     * currently holds is revoked. That last part matters: without it,
     * anyone who stole a refresh token before the password reset (the
     * exact scenario a reset is often responding to) would keep working
     * access via that token indefinitely, completely unaffected by the
     * password having changed underneath them. A password reset that
     * doesn't also invalidate existing sessions only half-solves the
     * problem it exists for.
     * <p>
     * Also clears any active lockout ({@code LoginAttemptService.resetFailedAttempts}).
     * Proving ownership of the account's email is a stronger identity
     * signal than a correct login password — if that's enough to change
     * the password, it's enough to also lift a lockout that exists
     * specifically to slow down someone who *doesn't* have that kind of
     * access.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken stored = passwordResetTokenRepository.findByTokenHash(TokenHasher.sha256Hex(request.getToken()))
                .filter(prt -> !prt.isUsed())
                .filter(prt -> prt.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid or expired"));

        stored.setUsed(true);
        User user = stored.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));

        refreshTokenRepository.revokeAllForUser(user.getId());
        loginAttemptService.resetFailedAttempts(user.getId());
    }

    /**
     * Unlike {@link #issueVerificationToken}'s link, this one deliberately
     * does NOT point at {@code /auth/reset-password} directly — that
     * endpoint is a {@code POST} expecting a new password in the body, so
     * a browser {@code GET} on it would just 405. Real password-reset
     * emails link to a web page with a form (which then calls the API),
     * not straight at the API itself; this project has no frontend, so
     * the link below is illustrative of where that page's URL would go,
     * carrying the token as a query param the way such a page typically
     * would.
     */
    private void issuePasswordResetToken(User user) {
        String rawToken = UUID.randomUUID().toString();

        PasswordResetToken token = PasswordResetToken.builder()
                .tokenHash(TokenHasher.sha256Hex(rawToken))
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(passwordResetProperties.tokenExpirationHours()))
                .build();
        passwordResetTokenRepository.save(token);

        String link = "%s/reset-password?token=%s".formatted(appProperties.baseUrl(), rawToken);
        emailService.send(user.getEmail(), "Reset your TaskFlow password",
                "Click the link below to reset your password:\n" + link
                        + "\n\nIf you didn't request this, you can safely ignore this email.");
    }

    private void issueVerificationToken(User user) {
        // UUID.randomUUID() is backed by SecureRandom in the JDK, not
        // Math.random() or similar — 122 bits of real entropy, not
        // guessable by iterating or timing.
        String rawToken = UUID.randomUUID().toString();

        VerificationToken token = VerificationToken.builder()
                .tokenHash(TokenHasher.sha256Hex(rawToken))
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(verificationProperties.tokenExpirationHours()))
                .build();
        verificationTokenRepository.save(token);

        String link = "%s/auth/verify?token=%s".formatted(appProperties.baseUrl(), rawToken);
        emailService.send(user.getEmail(), "Verify your TaskFlow account",
                "Click the link below to verify your account:\n" + link);
    }

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getEmail());

        RefreshToken entity = RefreshToken.builder()
                .tokenHash(TokenHasher.sha256Hex(refreshToken))
                .user(user)
                .expiresAt(LocalDateTime.now().plus(Duration.ofMillis(jwtProperties.refreshTokenExpirationMs())))
                .build();
        refreshTokenRepository.save(entity);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }
}

