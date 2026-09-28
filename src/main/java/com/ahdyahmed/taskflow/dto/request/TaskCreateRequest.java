package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Day 8: {@code createdById} is gone, same fix and same reasoning as
 * {@link ProjectCreateRequest} dropping {@code ownerId} — {@code TaskService.create}
 * now derives {@code createdBy} from the authenticated principal.
 * {@code status}/{@code priority} are optional; the service defaults them
 * to TODO/MEDIUM when omitted.
 */
@Getter
@Setter
public class TaskCreateRequest {

    @Schema(example = "Wire up the JWT filter")
    @NotBlank
    @Size(max = 255)
    private String title;

    @Schema(example = "OncePerRequestFilter that parses and validates the access token per request.")
    @Size(max = 2000)
    private String description;

    @Schema(example = "TODO", description = "Optional — defaults to TODO if omitted.")
    private TaskStatus status;

    @Schema(example = "HIGH", description = "Optional — defaults to MEDIUM if omitted.")
    private TaskPriority priority;

    @Schema(example = "1")
    @NotNull
    private Long projectId;

    @Schema(example = "2", description = "Optional — the task starts unassigned if omitted.")
    private Long assigneeId;
}
