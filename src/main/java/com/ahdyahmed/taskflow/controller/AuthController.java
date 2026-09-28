package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.EmailRequest;
import com.ahdyahmed.taskflow.dto.request.LoginRequest;
import com.ahdyahmed.taskflow.dto.request.RefreshRequest;
import com.ahdyahmed.taskflow.dto.request.RegisterRequest;
import com.ahdyahmed.taskflow.dto.request.ResetPasswordRequest;
import com.ahdyahmed.taskflow.dto.response.AuthResponse;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day 17: {@code @SecurityRequirements} with no {@code value} — not
 * {@code @SecurityRequirement}, and not omitted — is what tells
 * springdoc "no auth needed" for every endpoint in this class,
 * overriding the Bearer-JWT default {@link com.ahdyahmed.taskflow.config.OpenApiConfig}
 * applies to the rest of the API. Every endpoint here really is on
 * {@code SecurityConfig}'s {@code PUBLIC_AUTH_ENDPOINTS} list, so this
 * isn't a docs-only claim — Swagger UI simply wouldn't be able to send a
 * working request against any of these with a token attached that
 * mattered.
 */
@Tag(name = "Auth", description = "Registration, login, token lifecycle, email verification, and password reset. No endpoint here requires a Bearer token.")
@SecurityRequirements
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new account",
            description = "Creates a USER-role account (self-registration can't grant MANAGER/ADMIN) and sends a mocked verification email. The account can't log in until GET /auth/verify is called with the token from that email.")
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse created = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Log in",
            description = "Returns an access/refresh token pair on success. Fails with the same generic message whether the email doesn't exist, the password is wrong, or the account is locked out — deliberately, to avoid leaking which one it was.")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Exchange a refresh token for a new pair",
            description = "Rotates on use: the refresh token presented here is revoked the moment this call succeeds, and a brand-new pair is issued. Presenting the same refresh token again after that fails — it's single-use.")
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request);
    }

    @Operation(summary = "Log out",
            description = "Revokes the given refresh token server-side. Idempotent — calling this again with an already-revoked (or never-issued) token still returns 204, not an error.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
    }

    /**
     * Day 12: GET, not POST — this is meant to be a clickable link in an
     * email, and a link is a GET by nature. Mutating state (flipping
     * {@code enabled}) on a GET is a deliberate, pragmatic break from
     * REST purism, not an oversight — see the README's design decisions
     * for the reasoning and the precedent (every mainstream email-link
     * verification flow does the same thing).
     */
    @Operation(summary = "Verify an email address",
            description = "The link a mocked verification email points to. Activates the account so it can log in. A not-found, expired, or already-used token all fail with the same message — there's no legitimate reason to tell those apart from the response alone.")
    @GetMapping("/verify")
    public UserResponse verify(@RequestParam String token) {
        return authService.verify(token);
    }

    @Operation(summary = "Resend the verification email",
            description = "Always returns 204, whether or not the email belongs to an account, and whether or not that account is already verified — same enumeration-avoidance reasoning as /auth/forgot-password.")
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@Valid @RequestBody EmailRequest request) {
        authService.resendVerification(request);
    }

    @Operation(summary = "Request a password reset",
            description = "Always returns 204 regardless of whether the email exists — unlike registration's 409, revealing account existence here has a real attacker payoff, so the response stays uniform no matter what happened server-side.")
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody EmailRequest request) {
        authService.forgotPassword(request);
    }

    @Operation(summary = "Reset a password",
            description = "Validates the token from the forgot-password email, sets the new password, and — the detail worth knowing — revokes every refresh token the account currently holds, so a refresh token stolen before the reset stops working too.")
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
