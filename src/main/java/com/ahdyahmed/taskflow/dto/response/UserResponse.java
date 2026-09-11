package com.ahdyahmed.taskflow.dto.response;

import com.ahdyahmed.taskflow.domain.enums.Role;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class UserResponse {
    Long id;
    String email;
    Role role;
    boolean enabled;
    LocalDateTime createdAt;
}
