package com.ahdyahmed.taskflow.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * NOTE: {@code ownerId} is accepted directly from the client for now.
 * That's temporary and deliberately called out here — once authentication
 * exists (Day 4-6), the owner will be derived from the authenticated
 * principal instead of trusted from the request body.
 */
@Getter
@Setter
public class ProjectCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 1000)
    private String description;

    @NotNull
    private Long ownerId;
}
