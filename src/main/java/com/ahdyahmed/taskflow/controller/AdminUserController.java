package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.ChangeRoleRequest;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin on purpose — every rule here is enforced in {@link AdminUserService}
 * via a class-level {@code @PreAuthorize}, not here. This controller
 * doesn't need its own authorization check for the service to stay safe
 * if another caller invoked it directly.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public Page<UserResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return adminUserService.findAll(pageable);
    }

    @PutMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return adminUserService.changeRole(id, request.getRole());
    }
}
