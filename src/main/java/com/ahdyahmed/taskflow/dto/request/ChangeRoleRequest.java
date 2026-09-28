package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangeRoleRequest {

    @Schema(example = "MANAGER")
    @NotNull
    private Role role;
}
