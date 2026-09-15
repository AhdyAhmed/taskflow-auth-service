package com.ahdyahmed.taskflow.security;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Day 8. Referenced from {@code @PreAuthorize} as {@code @projectSecurity}
 * (the bean name below), e.g.
 * {@code @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isOwner(#id, authentication)")}.
 * <p>
 * A non-existent project resolves to {@code false} rather than throwing —
 * {@code @PreAuthorize} runs <em>before</em> the service method body, so a
 * denied check here becomes a 403 instead of the 404 the method would have
 * thrown. That's a deliberate trade-off, not an oversight: it means a
 * non-member can't use this endpoint to probe which project ids exist.
 * The ADMIN branch in the expression bypasses this bean entirely (Java
 * short-circuits {@code hasRole('ADMIN') or ...}), so an admin still gets
 * a real 404 for a project that truly doesn't exist.
 */
@Component("projectSecurity")
@RequiredArgsConstructor
public class ProjectSecurity {

    private final ProjectRepository projectRepository;

    /** True if the caller is the project's owner. Used to gate update/delete/member-management. */
    public boolean isOwner(Long projectId, Authentication authentication) {
        Long userId = AuthenticatedUser.id(authentication);
        return projectRepository.findById(projectId)
                .map(project -> isOwner(project, userId))
                .orElse(false);
    }

    /** True if the caller is the owner OR a member. Used to gate reads and task-level access into the project. */
    public boolean isMember(Long projectId, Authentication authentication) {
        Long userId = AuthenticatedUser.id(authentication);
        return projectRepository.findById(projectId)
                .map(project -> isOwner(project, userId) || isMember(project, userId))
                .orElse(false);
    }

    private boolean isOwner(Project project, Long userId) {
        return project.getOwner().getId().equals(userId);
    }

    private boolean isMember(Project project, Long userId) {
        return project.getMembers().stream().anyMatch(member -> member.getId().equals(userId));
    }
}
