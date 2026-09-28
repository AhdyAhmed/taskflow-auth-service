package com.ahdyahmed.taskflow.dto.response;

import com.ahdyahmed.taskflow.domain.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class UserResponse {
    @Schema(example = "1")
    Long id;
    @Schema(example = "alice@taskflow.dev")
    String email;
    @Schema(example = "USER")
    Role role;
    @Schema(example = "true", description = "False until the account's email has been verified.")
    boolean enabled;
    @Schema(example = "2026-01-15T09:30:00")
    LocalDateTime createdAt;
}
