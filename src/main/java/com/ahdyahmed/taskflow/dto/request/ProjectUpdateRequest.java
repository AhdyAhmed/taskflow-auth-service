package com.ahdyahmed.taskflow.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** PUT semantics: this replaces name/description in full, not a partial patch. */
@Getter
@Setter
public class ProjectUpdateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    @Size(max = 1000)
    private String description;
}
