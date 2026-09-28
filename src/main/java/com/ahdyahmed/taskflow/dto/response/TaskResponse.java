package com.ahdyahmed.taskflow.dto.response;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Never expose the Task entity directly — this is what the API actually returns. */
@Value
@Builder
public class TaskResponse {
    @Schema(example = "10")
    Long id;
    @Schema(example = "Wire up the JWT filter")
    String title;
    @Schema(example = "OncePerRequestFilter that parses and validates the access token per request.")
    String description;
    @Schema(example = "IN_PROGRESS")
    TaskStatus status;
    @Schema(example = "HIGH")
    TaskPriority priority;
    @Schema(example = "1")
    Long projectId;
    @Schema(example = "2")
    Long assigneeId;
    @Schema(example = "bob@taskflow.dev")
    String assigneeEmail;
    @Schema(example = "3")
    Long createdById;
    @Schema(example = "manager@taskflow.dev")
    String createdByEmail;
    @Schema(example = "2026-01-15T09:30:00")
    LocalDateTime createdAt;
    @Schema(example = "2026-02-01T14:05:00")
    LocalDateTime updatedAt;
}
