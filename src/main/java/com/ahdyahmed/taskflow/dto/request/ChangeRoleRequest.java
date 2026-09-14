package com.ahdyahmed.taskflow.dto.request;

import com.ahdyahmed.taskflow.domain.enums.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangeRoleRequest {

    @NotNull
    private Role role;
}
