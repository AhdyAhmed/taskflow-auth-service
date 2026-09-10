package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * NOTE: {@code createdById} is accepted directly from the client for now —
 * same temporary caveat as {@link ProjectCreateRequest#getOwnerId()}.
 * {@code status}/{@code priority} are optional; the service defaults them
 * to TODO/MEDIUM when omitted.
 */
@Getter
@Setter
public class TaskCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 2000)
    private String description;

    private TaskStatus status;

    private TaskPriority priority;

    @NotNull
    private Long projectId;

    private Long assigneeId;

    @NotNull
    private Long createdById;
}
