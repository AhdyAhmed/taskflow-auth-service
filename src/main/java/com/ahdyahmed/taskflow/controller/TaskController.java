package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.TaskCreateRequest;
import com.ahdyahmed.taskflow.dto.request.TaskUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.TaskResponse;
import com.ahdyahmed.taskflow.service.TaskService;
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
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskCreateRequest request,
                                                Authentication authentication) {
        TaskResponse created = taskService.create(request, authentication);
        return ResponseEntity.created(URI.create("/api/tasks/" + created.getId())).body(created);
    }

    @GetMapping("/{id}")
    public TaskResponse getById(@PathVariable Long id, Authentication authentication) {
        return taskService.findById(id, authentication);
    }

    @GetMapping
    public Page<TaskResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable,
                                    Authentication authentication) {
        return taskService.findAll(pageable, authentication);
    }

    @GetMapping("/project/{projectId}")
    public Page<TaskResponse> listByProject(@PathVariable Long projectId,
                                             @PageableDefault(size = 20, sort = "id") Pageable pageable,
                                             Authentication authentication) {
        return taskService.findByProject(projectId, pageable, authentication);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable Long id, @Valid @RequestBody TaskUpdateRequest request,
                                Authentication authentication) {
        return taskService.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        taskService.delete(id, authentication);
    }
}
