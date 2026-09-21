package com.ahdyahmed.taskflow.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Day 12: backs {@code POST /auth/resend-verification}. Kept generic
 * (just an email, no other fields) rather than named after that one
 * endpoint, since Day 13's {@code /auth/forgot-password} needs exactly
 * the same shape — one DTO, two endpoints, instead of two DTOs that
 * would only ever differ in name.
 */
@Getter
@Setter
public class EmailRequest {

    @NotBlank
    @Email
    private String email;
}
