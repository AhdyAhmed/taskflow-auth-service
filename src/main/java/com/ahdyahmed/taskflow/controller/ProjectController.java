package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.ProjectCreateRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.ProjectResponse;
import com.ahdyahmed.taskflow.service.ProjectService;
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
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectCreateRequest request,
                                                   Authentication authentication) {
        ProjectResponse created = projectService.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/projects/" + created.getId())).body(created);
    }

    @GetMapping("/{id}")
    public ProjectResponse getById(@PathVariable Long id, Authentication authentication) {
        return projectService.findById(id, authentication);
    }

    @GetMapping
    public Page<ProjectResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable,
                                       Authentication authentication) {
        return projectService.findAll(pageable, authentication);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectUpdateRequest request,
                                   Authentication authentication) {
        return projectService.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        projectService.delete(id, authentication);
    }

    /** Day 8: project owner (or ADMIN) adds someone as a member. */
    @PostMapping("/{id}/members/{userId}")
    public ProjectResponse addMember(@PathVariable Long id, @PathVariable Long userId,
                                      Authentication authentication) {
        return projectService.addMember(id, userId, authentication);
    }

    /** Day 8: project owner (or ADMIN) removes a member. */
    @DeleteMapping("/{id}/members/{userId}")
    public ProjectResponse removeMember(@PathVariable Long id, @PathVariable Long userId,
                                         Authentication authentication) {
        return projectService.removeMember(id, userId, authentication);
    }
}
