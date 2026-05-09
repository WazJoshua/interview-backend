package com.josh.interviewj.auth.controller;

import com.josh.interviewj.auth.dto.request.ForgotPasswordRequest;
import com.josh.interviewj.auth.dto.request.LoginRequest;
import com.josh.interviewj.auth.dto.request.RefreshTokenRequest;
import com.josh.interviewj.auth.dto.request.RegisterRequest;
import com.josh.interviewj.auth.dto.request.ResetPasswordRequest;
import com.josh.interviewj.auth.dto.response.PasswordKeyResponse;
import com.josh.interviewj.auth.dto.response.RegisterConfigResponse;
import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.auth.dto.response.AuthResponse;
import com.josh.interviewj.auth.dto.response.UserResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.auth.service.AuthService;
import com.josh.interviewj.auth.service.PasswordKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication endpoints for registration, login, token refresh, and profile retrieval.
 */
@Tag(name = "认证管理", description = "用户认证相关接口")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordKeyService passwordKeyService;

    /**
     * Registers a new user account.
     *
     * @param request registration payload
     * @return created user response
     */
    @Operation(summary = "用户注册", description = "注册新用户账号")
    @PostMapping("/register")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse userResponse = authService.register(request);
        return ApiResponse.created("Registration successful", userResponse);
    }

    @Operation(summary = "获取注册配置", description = "匿名获取注册页邀请码配置")
    @GetMapping("/register-config")
    public ApiResponse<RegisterConfigResponse> getRegisterConfig() {
        RegisterConfigResponse response = authService.getRegisterConfig();
        return ApiResponse.success(response);
    }

    @Operation(summary = "获取密码加密公钥", description = "匿名获取当前密码提交加密公钥")
    @GetMapping("/password-key")
    public ApiResponse<PasswordKeyResponse> getPasswordKey() {
        return ApiResponse.success(passwordKeyService.getCurrentKey());
    }

    /**
     * Authenticates user credentials and returns token pair.
     *
     * @param request login payload
     * @return authentication response with access/refresh tokens
     */
    @Operation(summary = "用户登录", description = "使用用户名密码登录获取Token")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse authResponse = authService.login(request);
        return ApiResponse.success("Login successful", authResponse);
    }

    @Operation(summary = "发起忘记密码", description = "签发密码重置令牌并发送通知")
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success("Password reset email accepted", null);
    }

    @Operation(summary = "重置密码", description = "使用 reset token 和新密码 envelope 完成重置")
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success("Password reset successful", null);
    }

    /**
     * Logs out the current user and invalidates the presented token.
     *
     * @param request current HTTP request
     * @return success response
     */
    @Operation(summary = "用户登出", description = "退出登录并使Token失效")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        String token = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            token = authorizationHeader.substring(7);
        }
        String username = getUsernameFromAuthentication();
        authService.logout(username, token);
        return ApiResponse.success("Logout successful", null);
    }

    /**
     * Exchanges refresh token for a new access token.
     *
     * @param request refresh token payload
     * @return refreshed authentication response
     */
    @Operation(summary = "刷新Token", description = "使用RefreshToken获取新的AccessToken")
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse authResponse = authService.refreshToken(request);
        return ApiResponse.success(authResponse);
    }

    /**
     * Fetches profile information for the current authenticated user.
     *
     * @return current user response
     */
    @Operation(summary = "获取当前用户", description = "获取当前登录用户信息")
    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser() {
        String username = getUsernameFromAuthentication();
        UserResponse userResponse = authService.getCurrentUserByUsername(username);
        return ApiResponse.success(userResponse);
    }

    /**
     * Resolves username from Spring Security context.
     *
     * @return authenticated username
     */
    private String getUsernameFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new BusinessException(ErrorCode.AUTH_001, "Unauthorized access");
        }
        return authentication.getName();
    }
}
