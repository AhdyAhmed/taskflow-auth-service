package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.config.JwtProperties;
import com.ahdyahmed.taskflow.domain.entity.RefreshToken;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.RefreshRequest;
import com.ahdyahmed.taskflow.dto.request.RegisterRequest;
import com.ahdyahmed.taskflow.dto.response.AuthResponse;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.exception.EmailAlreadyInUseException;
import com.ahdyahmed.taskflow.exception.InvalidCredentialsException;
import com.ahdyahmed.taskflow.exception.InvalidTokenException;
import com.ahdyahmed.taskflow.mapper.UserMapper;
import com.ahdyahmed.taskflow.repository.RefreshTokenRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import com.ahdyahmed.taskflow.security.AppUserPrincipal;
import com.ahdyahmed.taskflow.security.JwtService;
import com.ahdyahmed.taskflow.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final UserMapper userMapper;

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
                // TEMPORARY: true until Day 12 wires up email verification.
                // From that point on, new accounts start disabled and this
                // default flips to false.
                .enabled(true)
                .build();

        return userMapper.toResponse(userRepository.save(user));
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        AppUserPrincipal principal;
        try {
            var authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
            principal = (AppUserPrincipal) authentication.getPrincipal();
        } catch (AuthenticationException ex) {
            // Deliberately the same message whether the email doesn't
            // exist, the password is wrong, or (once Day 10-11 lands) the
            // account is locked — anything more specific here is a user
            // enumeration leak.
            throw new InvalidCredentialsException("Invalid email or password");
        }

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
