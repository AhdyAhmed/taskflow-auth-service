package com.ahdyahmed.taskflow.service;

import com.ahdyahmed.taskflow.domain.entity.User;
import com.ahdyahmed.taskflow.domain.enums.Role;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.exception.ResourceNotFoundException;
import com.ahdyahmed.taskflow.mapper.UserMapper;
import com.ahdyahmed.taskflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything here is ADMIN-only — this is the privileged path for role
 * assignment that {@code RegisterRequest} deliberately doesn't expose
 * (see Day 4's design decisions). The class-level {@code @PreAuthorize}
 * enforces that at the service layer, not just on the controller, so it
 * can't be bypassed by some other entry point calling this service
 * directly later.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public Page<UserResponse> findAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(userMapper::toResponse);
    }

    @Transactional
    public UserResponse changeRole(Long userId, Role newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User %d not found".formatted(userId)));
        user.setRole(newRole);
        return userMapper.toResponse(user);
    }
}
