package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.dto.request.ProjectCreateRequest;
import com.ahdyahmed.taskflow.dto.request.ProjectUpdateRequest;
import com.ahdyahmed.taskflow.dto.response.ProjectResponse;
import com.ahdyahmed.taskflow.exception.ResourceNotFoundException;
import com.ahdyahmed.taskflow.mapper.ProjectMapper;
import com.ahdyahmed.taskflow.repository.ProjectRepository;
import com.ahdyahmed.taskflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kept as a single concrete class rather than interface + impl: there's
 * only one implementation and no seam that needs swapping, so a service
 * interface here would be ceremony without a payoff. Class-level
 * {@code @Transactional(readOnly = true)} covers the read methods;
 * write methods override it with their own {@code @Transactional}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ProjectMapper projectMapper;

    @Transactional
    public ProjectResponse create(ProjectCreateRequest request) {
        User owner = userRepository.findById(request.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User %d not found".formatted(request.getOwnerId())));

        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .owner(owner)
                .build();

        return projectMapper.toResponse(projectRepository.save(project));
    }

    public ProjectResponse findById(Long id) {
        return projectMapper.toResponse(getProjectOrThrow(id));
    }

    public Page<ProjectResponse> findAll(Pageable pageable) {
        return projectRepository.findAll(pageable).map(projectMapper::toResponse);
    }

    @Transactional
    public ProjectResponse update(Long id, ProjectUpdateRequest request) {
        Project project = getProjectOrThrow(id);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        // No explicit save() call: `project` is a managed entity inside
        // this transaction, so Hibernate's dirty checking persists these
        // field changes on commit.
        return projectMapper.toResponse(project);
    }

    @Transactional
    public void delete(Long id) {
        projectRepository.delete(getProjectOrThrow(id));
    }

    private Project getProjectOrThrow(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project %d not found".formatted(id)));
    }
}
