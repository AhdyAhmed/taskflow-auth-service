package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Day 8. Referenced from {@code @PreAuthorize} as {@code @taskSecurity}.
 * See {@link ProjectSecurity} for why a not-found task resolves to
 * {@code false} (403) instead of throwing (which would surface as 404) —
 * same reasoning here, and the same ADMIN short-circuit applies.
 */
@Component("taskSecurity")
@RequiredArgsConstructor
public class TaskSecurity {

    private final TaskRepository taskRepository;

    /**
     * True if the caller can view/edit this specific task: the project's
     * owner, whoever created the task, or whoever it's assigned to. This
     * is the "own" half of "users can only edit their own tasks/projects
     * unless ADMIN or project MANAGER" (the roadmap's term for it) — a
     * MANAGER who isn't the project owner still needs
     * {@code hasRole('MANAGER')} combined with {@link ProjectSecurity#isMember}
     * to act on a task; this bean alone only covers the personal case.
     * Used to gate {@code update}.
     */
    public boolean isOwnerOrAssignee(Long taskId, Authentication authentication) {
        Long userId = AuthenticatedUser.id(authentication);
        return taskRepository.findById(taskId)
                .map(task -> isProjectOwner(task, userId) || isCreator(task, userId) || isAssignee(task, userId))
                .orElse(false);
    }

    /**
     * True only if the caller owns the task's project. Deliberately
     * narrower than {@link #isOwnerOrAssignee} — deleting a task is not
     * something the assignee (or even the person who created it, if
     * they've since left the project) should be able to do unilaterally;
     * that stays with whoever owns the project, or an ADMIN. Used to
     * gate {@code delete}.
     */
    public boolean isProjectOwner(Long taskId, Authentication authentication) {
        Long userId = AuthenticatedUser.id(authentication);
        return taskRepository.findById(taskId)
                .map(task -> isProjectOwner(task, userId))
                .orElse(false);
    }

    /**
     * True if the caller is a member (or owner) of the task's project.
     * Used to gate reads of a single task: "only members can view ... a
     * project's tasks" per the roadmap.
     */
    public boolean isProjectMember(Long taskId, Authentication authentication) {
        Long userId = AuthenticatedUser.id(authentication);
        return taskRepository.findById(taskId)
                .map(task -> isProjectMember(task, userId))
                .orElse(false);
    }

    private boolean isProjectOwner(Task task, Long userId) {
        return task.getProject().getOwner().getId().equals(userId);
    }

    private boolean isProjectMember(Task task, Long userId) {
        Project project = task.getProject();
        return project.getOwner().getId().equals(userId)
                || project.getMembers().stream().anyMatch(member -> member.getId().equals(userId));
    }

    private boolean isCreator(Task task, Long userId) {
        return task.getCreatedBy().getId().equals(userId);
    }

    private boolean isAssignee(Task task, Long userId) {
        return task.getAssignee() != null && task.getAssignee().getId().equals(userId);
    }
}
