package com.ahdyahmed.taskflow.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;

@Value
@Builder
public class ApiErrorResponse {
    @Schema(example = "2026-02-01T14:05:00Z")
    Instant timestamp;
    @Schema(example = "403")
    int status;
    @Schema(example = "Forbidden")
    String error;
    @Schema(example = "Access is denied")
    String message;
    @Schema(example = "/api/projects/7")
    String path;
    @Schema(description = "Populated only for bean-validation failures (400s); null otherwise.",
            example = "{\"email\": \"must be a well-formed email address\"}")
    Map<String, String> fieldErrors;
}
