package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.domain.entity.User;
import org.springframework.security.core.Authentication;

/**
 * Day 8: every ownership/membership check (and now {@code ProjectService}/
 * {@code TaskService} create methods, which derive {@code owner}/
 * {@code createdBy} from the caller instead of trusting it from the
 * request body — see the Day 1/3 note on those DTOs) needs the real
 * domain {@link User} behind the current {@code Authentication}. One
 * static helper here instead of every {@code @PreAuthorize} bean and
 * service repeating the same cast.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    public static User get(Authentication authentication) {
        return ((AppUserPrincipal) authentication.getPrincipal()).getUser();
    }

    public static Long id(Authentication authentication) {
        return get(authentication).getId();
    }
}
