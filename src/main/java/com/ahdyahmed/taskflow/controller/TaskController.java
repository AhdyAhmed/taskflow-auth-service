package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.TaskCreateRequest;
import com.ahdyahmed.taskflow.dto.request.TaskUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.TaskResponse;
import com.ahdyahmed.taskflow.service.TaskService;
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

/** Thin on purpose — see {@link ProjectController}'s note; same pattern here. */
@Tag(name = "Tasks", description = "Requires a Bearer access token. Creating a task needs MANAGER/ADMIN plus project membership; updating a task also allows its assignee, not just its creator or the project owner.")
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @Operation(summary = "Create a task", description = "Requires MANAGER or ADMIN, and membership on the target project.")
    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskCreateRequest request,
                                                Authentication authentication) {
        TaskResponse created = taskService.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/tasks/" + created.getId())).body(created);
    }

    @Operation(summary = "Get a task by id", description = "Requires membership on the task's project, or ADMIN.")
    @GetMapping("/{id}")
    public TaskResponse getById(@PathVariable Long id, Authentication authentication) {
        return taskService.findById(id, authentication);
    }

    @Operation(summary = "List tasks", description = "Returns tasks across every project the caller belongs to; ADMIN sees every task.")
    @GetMapping
    public Page<TaskResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable,
                                    Authentication authentication) {
        return taskService.findAll(pageable, authentication);
    }

    @Operation(summary = "List tasks in a project", description = "Requires membership on that project, or ADMIN.")
    @GetMapping("/project/{projectId}")
    public Page<TaskResponse> listByProject(@PathVariable Long projectId,
                                             @PageableDefault(size = 20, sort = "id") Pageable pageable,
                                             Authentication authentication) {
        return taskService.findByProject(projectId, pageable, authentication);
    }

    @Operation(summary = "Update a task", description = "Requires being the task's assignee, its creator, its project's owner, or ADMIN. Full replace — a null assigneeId clears the assignment rather than leaving it untouched.")
    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable Long id, @Valid @RequestBody TaskUpdateRequest request,
                                Authentication authentication) {
        return taskService.update(id, request, authentication);
    }

    @Operation(summary = "Delete a task", description = "Narrower than update: only the task's project owner or ADMIN can delete it — being the assignee or creator isn't enough.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        taskService.delete(id, authentication);
    }
}
