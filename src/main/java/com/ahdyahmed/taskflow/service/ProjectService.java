package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.Task;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.domain.enums.TaskStatus;
import com.ahdyahmed.taskflow.dto.request.ProjectCreateRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.ProjectResponse;
import com.ahdyahmed.taskflow.exception.MemberHasActiveAssignmentsException;
import com.ahdyahmed.taskflow.exception.ResourceNotFoundException;
import com.ahdyahmed.taskflow.mapper.ProjectMapper;
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

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kept as a single concrete class rather than interface + impl: there's
 * only one implementation and no seam that needs swapping, so a service
 * interface here would be ceremony without a payoff. Class-level
 * {@code @Transactional(readOnly = true)} covers the read methods;
 * write methods override it with their own {@code @Transactional}.
 * <p>
 * Day 8: the Day 7 "any MANAGER can write to any project" rule is now
 * ownership-scoped — see {@link com.ahdyahmed.taskflow.security.ProjectSecurity}.
 * Reads are membership-scoped too: {@link #findById} 403s for a
 * non-member, and {@link #findAll} silently filters to "projects I can
 * see" for anyone who isn't ADMIN, rather than exposing every project in
 * the system to every authenticated user.
 * <p>
 * Day 9: {@link #findAll} now validates {@code ?sort=} against
 * {@link #SORT_PROPERTIES} (see {@link SortValidation}), and
 * {@link #removeMember} refuses to remove a member who's still assigned
 * to an active task in the project — see that method's javadoc.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    /** Day 9: the only properties {@code GET /api/projects} accepts in {@code ?sort=}. */
    private static final Set<String> SORT_PROPERTIES = Set.of("id", "name", "createdAt", "updatedAt");

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final ProjectMapper projectMapper;

    /**
     * MANAGER+ only, same as Day 7 — but {@code owner} is no longer read
     * from the request; the caller creating the project is always its
     * owner. Nothing yet lets ownership be transferred to someone else
     * after the fact (not a requirement so far).
     */
    @PreAuthorize("hasRole('MANAGER')")
    @Transactional
    public ProjectResponse create(ProjectCreateRequest request, Authentication authentication) {
        User owner = AuthenticatedUser.get(authentication);

        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();

        return projectMapper.toResponse(projectRepository.save(project));
    }

    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isMember(#id, authentication)")
    public ProjectResponse findById(Long id, Authentication authentication) {
        return projectMapper.toResponse(getProjectOrThrow(id));
    }

    /**
     * ADMIN sees every project (useful for support/oversight); everyone
     * else sees only projects they own or are a member of. This is a
     * filtering concern, not an access-denial one, so it branches
     * in-method rather than living behind a {@code @PreAuthorize} — there's
     * no "wrong" role here, just a narrower result set.
     */
    public Page<ProjectResponse> findAll(Pageable pageable, Authentication authentication) {
        SortValidation.requireAllowed(pageable.getSort(), SORT_PROPERTIES);
        User caller = AuthenticatedUser.get(authentication);
        Page<Project> projects = caller.getRole() == Role.ADMIN
                ? projectRepository.findAll(pageable)
                : projectRepository.findAccessibleTo(caller.getId(), pageable);
        return projects.map(projectMapper::toResponse);
    }

    /**
     * Day 7 was {@code hasRole('MANAGER')} — any manager, any project.
     * Day 8 narrows that to "ADMIN, or the project's own owner": a
     * MANAGER who owns Project A has no special standing over Project B.
     */
    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isOwner(#id, authentication)")
    @Transactional
    public ProjectResponse update(Long id, ProjectUpdateRequest request, Authentication authentication) {
        Project project = getProjectOrThrow(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        // No explicit save() call: `project` is a managed entity inside
        // this transaction, so Hibernate's dirty checking persists these
        // field changes on commit.
        return projectMapper.toResponse(project);
    }

    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isOwner(#id, authentication)")
    @Transactional
    public void delete(Long id, Authentication authentication) {
        projectRepository.delete(getProjectOrThrow(id));
    }

    /**
     * Membership management is owner/ADMIN territory, same rule as
     * update/delete — adding or removing who can see and work on a
     * project is itself a project-level write.
     */
    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isOwner(#id, authentication)")
    @Transactional
    public ProjectResponse addMember(Long id, Long userId, Authentication authentication) {
        Project project = getProjectOrThrow(id);
        User member = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User %d not found".formatted(userId)));
        project.getMembers().add(member);
        return projectMapper.toResponse(project);
    }

    /**
     * Day 9: resolves the roadmap's open question — "what happens when a
     * MANAGER removes a member who's assigned tasks?" Answer: removal is
     * refused (409) while they're still the assignee on any non-DONE
     * task in this project. The alternative (silently unassigning, or
     * silently leaving a former member with edit rights over a task they
     * can no longer even see the project of — the Day 8 behavior this
     * replaces) both felt like the API making a judgment call on the
     * caller's behalf. Refusing puts that judgment call back where it
     * belongs: the owner reassigns or completes the task first, then
     * removal succeeds. DONE tasks don't count — see
     * {@code TaskRepository#findByProject_IdAndAssignee_IdAndStatusNot}.
     */
    @PreAuthorize("hasRole('ADMIN') or @projectSecurity.isOwner(#id, authentication)")
    @Transactional
    public ProjectResponse removeMember(Long id, Long userId, Authentication authentication) {
        Project project = getProjectOrThrow(id);

        List<Task> activeAssignments =
                taskRepository.findByProject_IdAndAssignee_IdAndStatusNot(id, userId, TaskStatus.DONE);
        if (!activeAssignments.isEmpty()) {
            String taskIds = activeAssignments.stream()
                    .map(task -> task.getId().toString())
                    .collect(Collectors.joining(", "));
            throw new MemberHasActiveAssignmentsException(
                    "Cannot remove member %d: still assigned to %d active task(s) (ids: %s). Reassign or complete them first."
                            .formatted(userId, activeAssignments.size(), taskIds));
        }

        project.getMembers().removeIf(member -> member.getId().equals(userId));
        return projectMapper.toResponse(project);
    }

    private Project getProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project %d not found".formatted(id)));
    }
}
