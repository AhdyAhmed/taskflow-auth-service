package com.ahdyahmed.taskflow.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AuthResponse {
    @Schema(example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZUB0YXNrZmxvdy5kZXYiLCJ0eXBlIjoiYWNjZXNzIn0...",
            description = "Short-lived (15 minutes). Paste just this into Swagger UI's Authorize dialog.")
    String accessToken;
    @Schema(example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZUB0YXNrZmxvdy5kZXYiLCJ0eXBlIjoicmVmcmVzaCJ9...",
            description = "Long-lived (7 days), single-use — rotates on every /auth/refresh call. Never sent as a Bearer token; only used against /auth/refresh and /auth/logout.")
    String refreshToken;
    @Schema(example = "Bearer")
    @Builder.Default
    String tokenType = "Bearer";
}
