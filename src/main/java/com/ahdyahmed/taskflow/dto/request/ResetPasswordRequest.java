package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Day 13. The new password goes through the same {@code @StrongPassword} rule as registration. */
@Getter
@Setter
public class ResetPasswordRequest {

    @NotBlank
    private String token;

    @NotBlank
    @StrongPassword
    private String newPassword;
}
