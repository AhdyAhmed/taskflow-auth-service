package com.ahdyahmed.taskflow.mapper;

import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.dto.response.TaskResponse;
import org.springframework.stereotype.Component;

@Component
public class TaskMapper {

    public TaskResponse toResponse(Task task) {
        User assignee = task.getAssignee();
        User createdBy = task.getCreatedBy();

        return TaskResponse.builder()
                .id(task.getId())
                .title(task.getTitle())
                .description(task.getDescription())
                .status(task.getStatus())
                .priority(task.getPriority())
                .projectId(task.getProject().getId())
                .assigneeId(assignee != null ? assignee.getId() : null)
                .assigneeEmail(assignee != null ? assignee.getEmail() : null)
                .createdById(createdBy.getId())
                .createdByEmail(createdBy.getEmail())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
