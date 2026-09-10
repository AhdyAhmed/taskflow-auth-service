package com.ahdyahmed.taskflow.dto.response;

import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Never expose the Task entity directly — this is what the API actually returns. */
@Value
@Builder
public class TaskResponse {
    Long id;
    String title;
    String description;
    TaskStatus status;
    TaskPriority priority;
    Long projectId;
    Long assigneeId;
    String assigneeEmail;
    Long createdById;
    String createdByEmail;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
