package com.josh.interviewj.auth.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.dto.request.UpdateUserRoleFlagsRequest;
import com.josh.interviewj.auth.dto.response.AdminUserRoleFlagsResponse;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.auth.service.AdminUserAccessService;
import com.josh.interviewj.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminUserAccessController {

    private final AdminAccessService adminAccessService;
    private final AdminUserAccessService adminUserAccessService;

    @GetMapping("/users/{userId}/role-flags")
    public ResponseEntity<ApiResponse<AdminUserRoleFlagsResponse>> getUserRoleFlags(
            Authentication authentication,
            @PathVariable UUID userId
    ) {
        adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminUserAccessService.getUserRoleFlags(userId)));
    }

    @PutMapping("/users/{userId}/role-flags")
    public ResponseEntity<ApiResponse<AdminUserRoleFlagsResponse>> updateUserRoleFlags(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserRoleFlagsRequest request
    ) {
        User actor = adminAccessService.requireAdmin(authentication);
        return ResponseEntity.ok(ApiResponse.success(adminUserAccessService.updateUserRoleFlags(actor.getId(), userId, request)));
    }
}
