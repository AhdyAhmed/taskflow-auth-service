package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Day 13. The new password goes through the same {@code @StrongPassword} rule as registration. */
@Getter
@Setter
public class ResetPasswordRequest {

    @Schema(example = "3f9c1e2a-...", description = "The token from the mocked forgot-password email link.")
    @NotBlank
    private String token;

    @Schema(example = "BrandNewSecret1!")
    @NotBlank
    @StrongPassword
    private String newPassword;
}
