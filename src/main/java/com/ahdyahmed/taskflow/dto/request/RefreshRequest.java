package com.ahdyahmed.taskflow.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Used by both POST /auth/refresh and POST /auth/logout — same shape, different semantics. */
@Getter
@Setter
public class RefreshRequest {

    @NotBlank
    private String refreshToken;
}
