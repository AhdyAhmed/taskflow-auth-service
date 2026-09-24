package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.config.AppProperties;
import com.ahdyahmed.taskflow.config.JwtProperties;
import com.ahdyahmed.taskflow.config.PasswordResetProperties;
import com.ahdyahmed.taskflow.config.VerificationProperties;
import com.ahdyahmed.taskflow.domain.entity.PasswordResetToken;
import com.ahdyahmed.taskflow.domain.entity.RefreshToken;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.ResetPasswordRequest;
import com.ahdyahmed.taskflow.dto.response.AuthResponse;
import com.ahdyahmed.taskflow.email.EmailService;
import com.ahdyahmed.taskflow.exception.AccountNotVerifiedException;
import com.ahdyahmed.taskflow.exception.InvalidCredentialsException;
import com.ahdyahmed.taskflow.exception.InvalidTokenException;
import com.ahdyahmed.taskflow.mapper.UserMapper;
import com.ahdyahmed.taskflow.repository.PasswordResetTokenRepository;
import com.ahdyahmed.taskflow.repository.RefreshTokenRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import com.ahdyahmed.taskflow.repository.VerificationTokenRepository;
import com.ahdyahmed.taskflow.security.AppUserPrincipal;
import com.ahdyahmed.taskflow.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Day 14. {@code AuthService} is exercised with everything mocked —
 * no Spring context, no database — since the point at this stage is the
 * service's own branching logic (which exception maps to what, what gets
 * called on success vs. failure), not wiring. The full-stack version of
 * these same flows (real DB, real filter chain, real 401/403/409 bodies)
 * is Day 15's job.
 * <p>
 * Covers: login success, login failure (bad credentials vs. unverified
 * account) and what each does or doesn't report to
 * {@link LoginAttemptService} (the lockout bookkeeping), and
 * {@code resetPassword}'s token-expiry/used-token/success paths.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private VerificationTokenRepository verificationTokenRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtService jwtService;
    @Mock
    private JwtProperties jwtProperties;
    @Mock
    private VerificationProperties verificationProperties;
    @Mock
    private PasswordResetProperties passwordResetProperties;
    @Mock
    private AppProperties appProperties;
    @Mock
    private UserMapper userMapper;
    @Mock
    private LoginAttemptService loginAttemptService;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private AuthService authService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L)
                .email("alice@taskflow.dev")
                .passwordHash("bcrypt-hash")
                .role(Role.USER)
                .enabled(true)
                .build();
    }

    // ---- login() ----------------------------------------------------

    @Test
    void login_validCredentials_issuesTokenPairAndResetsFailedAttempts() {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("Sup3rSecret");

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new AppUserPrincipal(user));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);

        when(jwtService.generateAccessToken(user.getEmail())).thenReturn("access-token");
        when(jwtService.generateRefreshToken(user.getEmail())).thenReturn("refresh-token");
        when(jwtProperties.refreshTokenExpirationMs()).thenReturn(604_800_000L);

        AuthResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");

        verify(loginAttemptService).resetFailedAttempts(user.getId());
        verify(loginAttemptService, never()).registerFailedAttempt(anyString());

        ArgumentCaptor<RefreshToken> savedTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(savedTokenCaptor.capture());
        assertThat(savedTokenCaptor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentialsAndRegistersFailedAttempt() {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("wrongpassword");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(loginAttemptService).registerFailedAttempt(user.getEmail());
        verify(loginAttemptService, never()).resetFailedAttempts(any());
        verify(jwtService, never()).generateAccessToken(anyString());
    }

    @Test
    void login_unverifiedAccount_throwsAccountNotVerified_withoutCountingAsFailedAttempt() {
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword("Sup3rSecret");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("Account is disabled"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(AccountNotVerifiedException.class);

        // The whole point of this branch (see AuthService's javadoc): a
        // correct password against an unverified account isn't
        // credential-guessing behavior and must never feed the lockout
        // counter.
        verify(loginAttemptService, never()).registerFailedAttempt(anyString());
        verify(loginAttemptService, never()).resetFailedAttempts(any());
    }

    // ---- resetPassword() --------------------------------------------

    @Test
    void resetPassword_expiredToken_throwsInvalidToken_andLeavesPasswordUnchanged() {
        PasswordResetToken expiredToken = PasswordResetToken.builder()
                .tokenHash("hashed-token")
                .user(user)
                .used(false)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(expiredToken));

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("raw-token");
        request.setNewPassword("NewSup3rSecret1");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(InvalidTokenException.class);

        verify(passwordEncoder, never()).encode(anyString());
        verify(refreshTokenRepository, never()).revokeAllForUser(any());
        assertThat(expiredToken.isUsed()).isFalse();
    }

    @Test
    void resetPassword_alreadyUsedToken_throwsInvalidToken() {
        PasswordResetToken usedToken = PasswordResetToken.builder()
                .tokenHash("hashed-token")
                .user(user)
                .used(true)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        when(passwordResetTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(usedToken));

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("raw-token");
        request.setNewPassword("NewSup3rSecret1");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(InvalidTokenException.class);

        verify(refreshTokenRepository, never()).revokeAllForUser(any());
    }

    @Test
    void resetPassword_unknownToken_throwsInvalidToken() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("raw-token");
        request.setNewPassword("NewSup3rSecret1");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetPassword_validToken_updatesPasswordRevokesRefreshTokensAndClearsLockout() {
        PasswordResetToken validToken = PasswordResetToken.builder()
                .tokenHash("hashed-token")
                .user(user)
                .used(false)
                .expiresAt(LocalDateTime.now().plusMinutes(30))
                .build();

        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(validToken));
        when(passwordEncoder.encode("NewSup3rSecret1")).thenReturn("new-bcrypt-hash");

        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("raw-token");
        request.setNewPassword("NewSup3rSecret1");

        authService.resetPassword(request);

        assertThat(validToken.isUsed()).isTrue();
        assertThat(user.getPasswordHash()).isEqualTo("new-bcrypt-hash");

        verify(refreshTokenRepository, times(1)).revokeAllForUser(user.getId());
        verify(loginAttemptService, times(1)).resetFailedAttempts(user.getId());
    }
}
