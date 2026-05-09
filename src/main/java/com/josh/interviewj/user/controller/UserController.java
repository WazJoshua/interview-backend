package com.josh.interviewj.user.controller;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.user.dto.request.ChangePasswordRequest;
import com.josh.interviewj.user.dto.request.UserUpdateRequest;
import com.josh.interviewj.user.dto.response.AvatarUploadResponse;
import com.josh.interviewj.user.dto.response.UserOverviewResponse;
import com.josh.interviewj.user.dto.response.UserProfileResponse;
import com.josh.interviewj.user.service.UserOverviewQueryService;
import com.josh.interviewj.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * User settings APIs.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "User profile APIs")
public class UserController {

    private final UserService userService;
    private final UserOverviewQueryService userOverviewQueryService;

    @GetMapping("/{userId}")
    @Operation(summary = "Get user profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(
            Authentication authentication,
            @PathVariable UUID userId) {

        UserProfileResponse response = userService.getProfile(userId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{userId}/overview")
    @Operation(summary = "Get user overview")
    public ResponseEntity<ApiResponse<UserOverviewResponse>> getOverview(
            Authentication authentication,
            @PathVariable UUID userId) {

        UserOverviewResponse response = userOverviewQueryService.getOverview(userId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "Update user profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestBody UserUpdateRequest request) {

        UserProfileResponse response = userService.updateProfile(userId, authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PutMapping("/{userId}/password")
    @Operation(summary = "Change password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @PathVariable UUID userId,
            @Valid @RequestBody ChangePasswordRequest request) {

        userService.changePassword(userId, authentication.getName(), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{userId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload avatar")
    public ResponseEntity<ApiResponse<AvatarUploadResponse>> uploadAvatar(
            Authentication authentication,
            @PathVariable UUID userId,
            @RequestParam("file") MultipartFile file) {

        AvatarUploadResponse response = userService.uploadAvatar(userId, authentication.getName(), file);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
