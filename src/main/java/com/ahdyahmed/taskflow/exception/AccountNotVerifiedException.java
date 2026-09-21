package com.ahdyahmed.taskflow.exception;

/**
 * Day 12: thrown from {@code AuthService.login()} when Spring Security's
 * {@code DaoAuthenticationProvider} rejects the attempt with a
 * {@code DisabledException} (i.e. {@code User.enabled == false}). Mapped
 * to {@code 403}, not {@code 401} — the credentials presented may well
 * be entirely correct; what's being refused is access given the
 * account's current state, which is exactly what 403 means as opposed
 * to 401's "who are you, again?". See {@code AuthService.login()}'s
 * javadoc for why this gets a distinct message at all, unlike
 * {@code InvalidCredentialsException}/lockout.
 */
public class AccountNotVerifiedException extends RuntimeException {

    public AccountNotVerifiedException(String message) {
        super(message);
    }
}
