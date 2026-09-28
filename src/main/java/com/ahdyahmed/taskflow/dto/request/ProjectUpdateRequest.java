package com.ahdyahmed.taskflow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** PUT semantics: this replaces name/description in full, not a partial patch. */
@Getter
@Setter
public class ProjectUpdateRequest {

    @Schema(example = "TaskFlow Backend Rewrite (Phase 2)")
    @NotBlank
    @Size(max = 255)
    private String name;

    @Schema(example = "Migrate the legacy REST API to the new Spring Boot service. Phase 2: auth.")
    @Size(max = 1000)
    private String description;
}
