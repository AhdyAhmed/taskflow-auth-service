package com.ahdyahmed.taskflow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @Schema(example = "alice@taskflow.dev")
    @NotBlank
    private String email;

    @Schema(example = "Sup3rSecretPassword!")
    @NotBlank
    private String password;
}
