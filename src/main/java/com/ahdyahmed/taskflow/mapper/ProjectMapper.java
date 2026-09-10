package com.ahdyahmed.taskflow.mapper;

import com.ahdyahmed.taskflow.domain.entity.Project;
import com.ahdyahmed.taskflow.dto.response.ProjectResponse;
import org.springframework.stereotype.Component;

/**
 * Manual mapping, on purpose — a mapping framework (MapStruct etc.) is
 * more useful once there are many DTOs with overlapping shapes. At this
 * scale, hand-written mapping is a few lines, has zero build-time magic,
 * and is trivial to step through in a debugger.
 */
@Component
public class ProjectMapper {

    public ProjectResponse toResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .ownerId(project.getOwner().getId())
                .ownerEmail(project.getOwner().getEmail())
                .memberCount(project.getMembers().size())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
