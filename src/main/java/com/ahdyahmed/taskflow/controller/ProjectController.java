package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.ProjectCreateRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.ProjectResponse;
import com.ahdyahmed.taskflow.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Thin on purpose, same as {@code AdminUserController} — every rule
 * (role, ownership, membership) is enforced in {@link ProjectService},
 * not here. {@code Authentication} is just threaded through to the
 * service; Spring resolves it from the security context automatically
 * for any authenticated request, no extra wiring needed.
 */
@Tag(name = "Projects", description = "Requires a Bearer access token. Creating a project needs MANAGER or ADMIN; reading/updating/deleting one needs membership/ownership on top of that — see the README's authorization rules.")
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "Create a project", description = "Requires MANAGER or ADMIN. The caller becomes the project's owner — there's no way to create a project on someone else's behalf.")
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request,
                                                   Authentication authentication) {
        ProjectResponse created = projectService.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/projects/" + created.getId())).body(created);
    }

    @Operation(summary = "Get a project by id", description = "Requires membership (owner or added member) unless the caller is ADMIN.")
    @GetMapping("/{id}")
    public ProjectResponse getById(@PathVariable Long id, Authentication authentication) {
        return projectService.findById(id, authentication);
    }

    @Operation(summary = "List projects", description = "Returns the projects the caller owns or is a member of; ADMIN sees every project.")
    @GetMapping
    public Page<ProjectResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable,
                                       Authentication authentication) {
        return projectService.findAll(pageable, authentication);
    }

    @Operation(summary = "Update a project", description = "Requires being this project's owner, or ADMIN — being a MANAGER elsewhere isn't sufficient on its own. Full replace, not a partial patch.")
    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request,
                                   Authentication authentication) {
        return projectService.update(id, request, authentication);
    }

    @Operation(summary = "Delete a project", description = "Requires being this project's owner, or ADMIN.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        projectService.delete(id, authentication);
    }

    @Operation(summary = "Add a project member", description = "Day 8: project owner (or ADMIN) adds someone as a member.")
    @PostMapping("/{id}/members/{userId}")
    public ProjectResponse addMember(@PathVariable Long id, @PathVariable Long userId,
                                      Authentication authentication) {
        return projectService.addMember(id, userId, authentication);
    }

    @Operation(summary = "Remove a project member", description = "Day 8: project owner (or ADMIN) removes a member.")
    @DeleteMapping("/{id}/members/{userId}")
    public ProjectResponse removeMember(@PathVariable Long id, @PathVariable Long userId,
                                         Authentication authentication) {
        return projectService.removeMember(id, userId, authentication);
    }
}
