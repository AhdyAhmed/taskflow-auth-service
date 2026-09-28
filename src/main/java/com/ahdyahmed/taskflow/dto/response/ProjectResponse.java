package com.ahdyahmed.taskflow.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Never expose the Project entity directly — this is what the API actually returns. */
@Value
@Builder
public class ProjectResponse {
    @Schema(example = "1")
    Long id;
    @Schema(example = "TaskFlow Backend Rewrite")
    String name;
    @Schema(example = "Migrate the legacy REST API to the new Spring Boot service.")
    String description;
    @Schema(example = "3")
    Long ownerId;
    @Schema(example = "manager@taskflow.dev")
    String ownerEmail;
    @Schema(example = "4")
    int memberCount;
    @Schema(example = "2026-01-15T09:30:00")
    LocalDateTime createdAt;
    @Schema(example = "2026-02-01T14:05:00")
    LocalDateTime updatedAt;
}
