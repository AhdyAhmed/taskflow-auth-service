package com.ahdyahmed.taskflow.dto.response;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Never expose the Project entity directly — this is what the API actually returns. */
@Value
@Builder
public class ProjectResponse {
    Long id;
    String name;
    String description;
    Long ownerId;
    String ownerEmail;
    int memberCount;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
