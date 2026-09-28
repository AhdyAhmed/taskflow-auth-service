package com.ahdyahmed.taskflow.controller;

import com.ahdyahmed.taskflow.dto.request.ChangeRoleRequest;
import com.ahdyahmed.taskflow.dto.response.UserResponse;
import com.ahdyahmed.taskflow.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Admin", description = "Requires a Bearer access token belonging to an ADMIN — every endpoint here is ADMIN-only.")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "List all users", description = "ADMIN only.")
    @GetMapping
    public Page<UserResponse> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return adminUserService.findAll(pageable);
    }

    @Operation(summary = "Change a user's role", description = "ADMIN only. The only way a MANAGER or ADMIN role gets granted in this system — self-registration always creates a USER.")
    @PutMapping("/{id}/role")
    public UserResponse changeRole(@PathVariable Long id, @Valid @RequestBody ChangeRoleRequest request) {
        return adminUserService.changeRole(id, request.getRole());
    }
}
