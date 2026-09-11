package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.validation.StrongPassword;
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

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @StrongPassword
    private String password;
}
