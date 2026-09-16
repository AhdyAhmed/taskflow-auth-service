package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
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
import com.ahdyahmed.taskflow.security.AuthenticatedUser;
import com.ahdyahmed.taskflow.web.SortValidation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Day 8: Day 7's "any MANAGER can write to any task, anywhere" rule is
 * gone. {@code create} still requires the MANAGER role, but now also
 * project membership — a MANAGER can't create tasks in a project they
 * have nothing to do with. {@code update} drops the role check entirely
 * in favor of {@code @taskSecurity.isOwnerOrAssignee}: the task's
 * project owner, its creator, or whoever it's assigned to — "the person
 * a task is assigned to can update it themselves" from the Day 7 note
 * now actually holds, and a MANAGER with no relationship to this
 * specific task no longer gets a free pass. {@code delete} is narrower
 * still: project owner or ADMIN only, not the creator or assignee (see
 * {@link com.ahdyahmed.taskflow.security.TaskSecurity#isProjectOwner}).
 * Reads are membership-scoped the same way as {@link ProjectService}.
 * <p>
 * Day 9: {@link #findAll} and {@link #findByProject} validate
 * {@code ?sort=} against {@link #SORT_PROPERTIES} — see
 * {@link com.ahdyahmed.taskflow.web.SortValidation}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {

    /** Day 9: the only properties {@code GET /api/tasks}(/project/{id}) accepts in {@code ?sort=}. */
    private static final Set<String> SORT_PROPERTIES =
            Set.of("id", "title", "status", "priority", "createdAt", "updatedAt");

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TaskMapper taskMapper;

    @PreAuthorize("hasRole('ADMIN') or (hasRole('MANAGER') and @projectSecurity.isMember(#request.projectId, authentication))")
    @Transactional
    public TaskResponse create(TaskCreateRequest request, Authentication authentication) {
        Project project = projectRepository.findById(request.getProjectId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project %d not found".formatted(request.getProjectId())));

        User createdBy = AuthenticatedUser.get(authentication);
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

    @PreAuthorize("hasRole('ADMIN') or @taskSecurity.isProjectMember(#id, authentication)")
    public TaskResponse findById(Long id, Authentication authentication) {
        return taskMapper.toResponse(getTaskOrThrow(id));
    }

    /** Same admin-sees-all / everyone-else-sees-their-own split as {@link ProjectService#findAll}. */
    public Page<TaskResponse> findAll(Pageable pageable, Authentication authentication) {
        SortValidation.requireAllowed(pageable.getSort(), SORT_PROPERTIES);
        User caller = AuthenticatedUser.get(authentication);
        Page<Task> tasks = caller.getRole() == Role.ADMIN
                ? taskRepository.findAll(pageable)
                : taskRepository.findAccessibleTo(caller.getId(), pageable);
        return tasks.map(taskMapper::toResponse);
    }

    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isMember(#projectId, authentication)")
    public Page<TaskResponse> findByProject(Long projectId, Pageable pageable, Authentication authentication) {
        SortValidation.requireAllowed(pageable.getSort(), SORT_PROPERTIES);
        if (!projectRepository.existsById(projectId)) {
            throw new ResourceNotFoundException("Project %d not found".formatted(projectId));
        }
        return taskRepository.findByProject_Id(projectId, pageable).map(taskMapper::toResponse);
    }

    @PreAuthorize("hasRole('ADMIN') or @taskSecurity.isOwnerOrAssignee(#id, authentication)")
    @Transactional
    public TaskResponse update(Long id, TaskUpdateRequest request, Authentication authentication) {
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

    @PreAuthorize("hasRole('ADMIN') or @taskSecurity.isProjectOwner(#id, authentication)")
    @Transactional
    public void delete(Long id, Authentication authentication) {
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
