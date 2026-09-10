package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
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

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 2000)
    private String description;

    private TaskStatus status;

    private TaskPriority priority;

    private Long assigneeId;
}
