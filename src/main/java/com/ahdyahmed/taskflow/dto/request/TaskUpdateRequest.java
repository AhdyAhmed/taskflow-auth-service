package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * PUT semantics: this replaces the task in full. In particular, a null
 * {@code assigneeId} means "unassigned" — omitting it doesn't leave the
 * previous assignee untouched, it clears it. A partial-update (PATCH)
 * endpoint can be added later if that turns out to be too blunt in
 * practice.
 */
@Getter
@Setter
public class TaskUpdateRequest {

    @Schema(example = "Wire up the JWT filter (done)")
    @NotBlank
    @Size(max = 255)
    private String title;

    @Schema(example = "OncePerRequestFilter that parses and validates the access token per request.")
    @Size(max = 2000)
    private String description;

    @Schema(example = "IN_PROGRESS")
    private TaskStatus status;

    @Schema(example = "HIGH")
    private TaskPriority priority;

    @Schema(example = "2", description = "Null clears the assignment — this is a full replace, not a partial patch.")
    private Long assigneeId;
}
