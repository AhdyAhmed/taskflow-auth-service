package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.TaskPriority;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import com.ahdyahmed.taskflow.dto.request.TaskCreateRequest;
import com.ahdyahmed.taskflow.dto.request.TaskUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.TaskResponse;
import com.ahdyahmed.taskflow.exception.ResourceNotFoundException;
import com.ahdyahmed.taskflow.mapper.TaskMapper;
import com.ahdyahmed.taskflow.repository.ProjectRepository;
import com.ahdyahmed.taskflow.repository.TaskRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Day 7: writes require MANAGER (ADMIN implied via the role hierarchy).
 * There's no ownership carve-out for the assignee yet — even the person
 * a task is assigned to can't update it themselves today. Day 8-9 adds
 * {@code @PreAuthorize("hasRole('MANAGER') or @taskSecurity.isAssignee(...)")}
 * on top of this, so a USER gains exactly the "update tasks assigned to
 * me" right the project brief calls for, without loosening anything else.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskMapper taskMapper;

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public TaskResponse create(TaskCreateRequest request) {
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project %d not found".formatted(request.getProjectId())));

        User createdBy = userRepository.findById(request.getCreatedById())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User %d not found".formatted(request.getCreatedById())));

        User assignee = resolveAssignee(request.getAssigneeId());

        Task task = Task.builder()
                .title(request.getTitle())
                .description(request.getDescription())
                .status(request.getStatus() != null ? request.getStatus() : TaskStatus.TODO)
                .priority(request.getPriority() != null ? request.getPriority() : TaskPriority.MEDIUM)
                .project(project)
                .assignee(assignee)
                .createdBy(createdBy)
                .build();

        return taskMapper.toResponse(taskRepository.save(task));
    }

    public TaskResponse findById(Long id) {
        return taskMapper.toResponse(getTaskOrThrow(id));
    }

    public Page<TaskResponse> findAll(Pageable pageable) {
        return taskRepository.findAll(pageable).map(taskMapper::toResponse);
    }

    public Page<TaskResponse> findByProject(Long projectId, Pageable pageable) {
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project %d not found".formatted(projectId));
        }
        return taskRepository.findByProject_Id(projectId, pageable).map(taskMapper::toResponse);
    }

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public TaskResponse update(Long id, TaskUpdateRequest request) {
        Task task = getTaskOrThrow(id);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setStatus(request.getStatus() != null ? request.getStatus() : task.getStatus());
        task.setPriority(request.getPriority() != null ? request.getPriority() : task.getPriority());
        // PUT = full replace: a null assigneeId here means "unassigned",
        // not "leave whatever was there before".
        task.setAssignee(resolveAssignee(request.getAssigneeId()));
        return taskMapper.toResponse(task);
    }

    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public void delete(Long id) {
        taskRepository.delete(getTaskOrThrow(id));
    }

    private User resolveAssignee(Long assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        return userRepository.findById(assigneeId)
                .orElseThrow(() -> new ResourceNotFoundException("User %d not found".formatted(assigneeId)));
    }

    private Task getTaskOrThrow(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task %d not found".formatted(id)));
    }
}
