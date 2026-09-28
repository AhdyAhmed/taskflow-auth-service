package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * No {@code role} field here, on purpose. Every self-registered account
 * is a USER — elevating someone to MANAGER/ADMIN happens through a
 * separate, privileged path once RBAC exists (Day 7-9), never by letting
 * the client assign its own role at signup.
 */
@Getter
@Setter
public class RegisterRequest {

    @Schema(example = "alice@taskflow.dev")
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @Schema(example = "Sup3rSecretPassword!", description = "At least 8 characters, with an uppercase letter, a lowercase letter, and a digit — see @StrongPassword.")
    @NotBlank
    @StrongPassword
    private String password;
}
