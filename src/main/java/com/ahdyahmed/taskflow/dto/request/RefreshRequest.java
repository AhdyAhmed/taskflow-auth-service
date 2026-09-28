package com.ahdyahmed.taskflow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Used by both POST /auth/refresh and POST /auth/logout — same shape, different semantics. */
@Getter
@Setter
public class RefreshRequest {

    @Schema(example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZUB0YXNrZmxvdy5kZXYiLCJ0eXBlIjoicmVmcmVzaCJ9...",
            description = "The refreshToken from a login/register-verify/refresh response — not the accessToken.")
    @NotBlank
    private String refreshToken;
}
