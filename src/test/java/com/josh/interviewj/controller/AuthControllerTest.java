package com.josh.interviewj.controller;

import com.josh.interviewj.auth.controller.AuthController;
import com.josh.interviewj.auth.dto.request.LoginRequest;
import com.josh.interviewj.auth.dto.request.ForgotPasswordRequest;
import com.josh.interviewj.auth.dto.request.RefreshTokenRequest;
import com.josh.interviewj.auth.dto.request.RegisterRequest;
import com.josh.interviewj.auth.dto.request.ResetPasswordRequest;
import com.josh.interviewj.auth.dto.response.AuthResponse;
import com.josh.interviewj.auth.dto.response.InviteCodeFormatResponse;
import com.josh.interviewj.auth.dto.response.RegisterConfigResponse;
import com.josh.interviewj.auth.dto.response.UserResponse;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.auth.service.AuthService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hamcrest.Matchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

        @Mock
        private AuthService authService;

        @InjectMocks
        private AuthController authController;

        private MockMvc mockMvc;

        @BeforeEach
        void setUp() {
                mockMvc = MockMvcBuilders.standaloneSetup(authController)
                                .setControllerAdvice(new GlobalExceptionHandler())
                                .build();
        }

        @Test
        void register_Success_ReturnsCreatedResponse() throws Exception {
                UserResponse userResponse = new UserResponse();
                userResponse.setId(UUID.randomUUID());
                userResponse.setUsername("testuser");
                userResponse.setEmail("test@example.com");

                when(authService.register(any(RegisterRequest.class))).thenReturn(userResponse);

                String requestBody = """
                                {
                                    "username": "testuser",
                                    "email": "test@example.com",
                                    "passwordEnvelope": {
                                        "keyId": "pwd-key-20260329",
                                        "nonce": "nonce-register-1",
                                        "timestamp": 1743211200000,
                                        "ciphertext": "ciphertext-register"
                                    },
                                    "inviteCode": "ABCD-WXYZ-2345"
                                }
                                """;

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk()) // Handled by ApiResponse wrapper logic typically yielding
                                                            // 200/201 depending
                                                            // on annotation. Wait, ApiResponse wrapper is returned,
                                                            // controller returns
                                                            // 200 status with JSON containing code=201 usually. Testing
                                                            // standard Spring
                                                            // behavior: @PostMapping doesn't specify status unless
                                                            // @ResponseStatus is
                                                            // used, so it returns 200 OK.
                                .andExpect(jsonPath("$.code").value(201))
                        .andExpect(jsonPath("$.message").value("Registration successful"))
                                .andExpect(jsonPath("$.data.username").value("testuser"));

                verify(authService).register(any(RegisterRequest.class));
        }

        @Test
        void register_PlaintextPasswordRejected_ReturnsBadRequest() throws Exception {
                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validLegacyRegisterRequestBody()))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
        }

        @Test
        void getRegisterConfig_Success_ReturnsInviteCodeContract() throws Exception {
                RegisterConfigResponse response = RegisterConfigResponse.builder()
                                .inviteCodeRequired(true)
                                .inviteCodeFormat(InviteCodeFormatResponse.builder()
                                                .length(12)
                                                .displayPattern("XXXX-XXXX-XXXX")
                                                .caseSensitive(false)
                                                .allowWhitespace(true)
                                                .allowHyphen(true)
                                                .build())
                                .build();

                when(authService.getRegisterConfig()).thenReturn(response);

                mockMvc.perform(get("/api/v1/auth/register-config"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data.inviteCodeRequired").value(true))
                                .andExpect(jsonPath("$.data.inviteCodeFormat.length").value(12))
                                .andExpect(jsonPath("$.data.inviteCodeFormat.displayPattern").value("XXXX-XXXX-XXXX"))
                                .andExpect(jsonPath("$.data.inviteCodeFormat.caseSensitive").value(false))
                                .andExpect(jsonPath("$.data.inviteCodeFormat.allowWhitespace").value(true))
                                .andExpect(jsonPath("$.data.inviteCodeFormat.allowHyphen").value(true));

                verify(authService).getRegisterConfig();
        }

        @Test
        void forgotPassword_Success_ReturnsAcceptedResponse() throws Exception {
                mockMvc.perform(post("/api/v1/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                    "email": "test@example.com"
                                                }
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.message").value("Password reset email accepted"));

                verify(authService).forgotPassword(any(ForgotPasswordRequest.class));
        }

        @Test
        void resetPassword_Success_ReturnsAcceptedResponse() throws Exception {
                mockMvc.perform(post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                    "resetToken": "reset-token-001",
                                                    "newPasswordEnvelope": {
                                                        "keyId": "pwd-key-20260329",
                                                        "nonce": "nonce-reset-1",
                                                        "timestamp": 1743211200000,
                                                        "ciphertext": "ciphertext-reset"
                                                    }
                                                }
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.message").value("Password reset successful"));

                verify(authService).resetPassword(any(ResetPasswordRequest.class));
        }

        @Test
        void resetPassword_InvalidToken_ReturnsBadRequest() throws Exception {
                doThrow(new BusinessException("AUTH_008", "Password reset token is invalid or expired"))
                                .when(authService).resetPassword(any(ResetPasswordRequest.class));

                mockMvc.perform(post("/api/v1/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                    "resetToken": "expired-token",
                                                    "newPasswordEnvelope": {
                                                        "keyId": "pwd-key-20260329",
                                                        "nonce": "nonce-reset-2",
                                                        "timestamp": 1743211200000,
                                                        "ciphertext": "ciphertext-reset"
                                                    }
                                                }
                                                """))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error.type").value("AUTH_008"));
        }

        @Test
        void register_InviteRequiredError_ReturnsBadRequest() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.INVITE_001, "Invite code is required"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value(400))
                                .andExpect(jsonPath("$.error.type").value("INVITE_001"))
                                .andExpect(jsonPath("$.timestamp", Matchers.matchesPattern(".*Z$")));
        }

        @Test
        void register_InviteInvalidError_ReturnsBadRequest() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.INVITE_002, "Invite code is invalid"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value(400))
                                .andExpect(jsonPath("$.error.type").value("INVITE_002"));
        }

        @Test
        void register_InviteUsedError_ReturnsConflict() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.INVITE_003, "Invite code has already been used"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value(409))
                                .andExpect(jsonPath("$.error.type").value("INVITE_003"));
        }

        @Test
        void register_InviteExpiredError_ReturnsConflict() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.INVITE_004, "Invite code has expired"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value(409))
                                .andExpect(jsonPath("$.error.type").value("INVITE_004"));
        }

        @Test
        void register_UsernameConflict_ReturnsConflict() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.USER_001, "Username already exists"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value(409))
                                .andExpect(jsonPath("$.error.type").value("USER_001"));
        }

        @Test
        void register_EmailConflict_ReturnsConflict() throws Exception {
                when(authService.register(any(RegisterRequest.class)))
                                .thenThrow(new BusinessException(ErrorCode.USER_002, "Email already registered"));

                mockMvc.perform(post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRegisterRequestBody()))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value(409))
                                .andExpect(jsonPath("$.error.type").value("USER_002"));
        }

        @Test
        void login_Success_ReturnsAuthResponse() throws Exception {
                AuthResponse authResponse = new AuthResponse();
                authResponse.setAccessToken("token123");
                authResponse.setRefreshToken("refresh123");

                UserResponse userResponse = new UserResponse();
                userResponse.setId(UUID.randomUUID());
                userResponse.setUsername("testuser");
                userResponse.setPhone("13800138000");
                userResponse.setLocale("zh-CN");
                userResponse.setTimezone("Asia/Shanghai");
                userResponse.setLastLoginAt(LocalDateTime.of(2026, 3, 13, 12, 30));
                userResponse.setRoles(Set.of("USER"));
                authResponse.setUser(userResponse);

                when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

                String requestBody = """
                                {
                                    "username": "testuser",
                                    "passwordEnvelope": {
                                        "keyId": "pwd-key-20260329",
                                        "nonce": "nonce-login-1",
                                        "timestamp": 1743211200000,
                                        "ciphertext": "ciphertext-login"
                                    }
                                }
                                """;

                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.message").value("Login successful"))
                                .andExpect(jsonPath("$.timestamp", Matchers.matchesPattern(".*Z$")))
                                .andExpect(jsonPath("$.data.accessToken").value("token123"))
                                .andExpect(jsonPath("$.data.user.phone").value("13800138000"))
                                .andExpect(jsonPath("$.data.user.locale").value("zh-CN"))
                                .andExpect(jsonPath("$.data.user.timezone").value("Asia/Shanghai"))
                                .andExpect(jsonPath("$.data.user.lastLoginAt").value("2026-03-13T12:30:00"))
                                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"));

                verify(authService).login(any(LoginRequest.class));
        }

        @Test
        void login_PlaintextPasswordRejected_ReturnsBadRequest() throws Exception {
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                    "username": "testuser",
                                                    "password": "Password123"
                                                }
                                                """))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
        }

        @Test
        void login_PasswordEnvelopeProtocolError_ReturnsBadRequest() throws Exception {
                when(authService.login(any(LoginRequest.class)))
                                .thenThrow(new BusinessException(
                                                "AUTH_007",
                                                "Password encryption payload is invalid",
                                                java.util.List.of(
                                                                com.josh.interviewj.common.exception.ErrorResponse.ErrorDetails.ErrorDetail
                                                                                .builder()
                                                                                .field("passwordEnvelope.keyId")
                                                                                .code("unknown_key_id")
                                                                                .message("Password key is invalid or expired")
                                                                                .build())));

                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validLoginRequestBody()))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value(400))
                                .andExpect(jsonPath("$.error.type").value("AUTH_007"))
                                .andExpect(jsonPath("$.error.details[0].field").value("passwordEnvelope.keyId"))
                                .andExpect(jsonPath("$.error.details[0].code").value("unknown_key_id"));
        }

        @Test
        void logout_Success_ReturnsVoidResponse() throws Exception {
                Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A",
                                java.util.Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

                mockMvc.perform(post("/api/v1/auth/logout")
                                .header("Authorization", "Bearer token123")
                                .principal(authentication))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.message").value("Logout successful"));

                verify(authService).logout(eq("testuser"), eq("token123"));
                SecurityContextHolder.clearContext();
        }

        @Test
        void logout_WithoutToken_Success_ReturnsVoidResponse() throws Exception {
                Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A",
                                java.util.Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

                mockMvc.perform(post("/api/v1/auth/logout")
                                .principal(authentication))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                        .andExpect(jsonPath("$.message").value("Logout successful"));

                verify(authService).logout(eq("testuser"), eq(null));
                SecurityContextHolder.clearContext();
        }

        @Test
        void refreshToken_Success_ReturnsAuthResponse() throws Exception {
                AuthResponse authResponse = new AuthResponse();
                authResponse.setAccessToken("new_token123");
                authResponse.setRefreshToken("new_refresh123");

                when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(authResponse);

                String requestBody = """
                                {
                                    "refreshToken": "old_refresh123"
                                }
                                """;

                mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data.accessToken").value("new_token123"));

                verify(authService).refreshToken(any(RefreshTokenRequest.class));
        }

        @Test
        void getCurrentUser_Success_ReturnsUserResponse() throws Exception {
                UserResponse userResponse = new UserResponse();
                userResponse.setId(UUID.randomUUID());
                userResponse.setUsername("testuser");
                userResponse.setEmail("test@example.com");
                userResponse.setPhone("13800138000");
                userResponse.setLocale("zh-CN");
                userResponse.setTimezone("Asia/Shanghai");
                userResponse.setLastLoginAt(LocalDateTime.of(2026, 3, 13, 8, 15));
                userResponse.setRoles(Set.of("USER"));

                when(authService.getCurrentUserByUsername("testuser")).thenReturn(userResponse);

                Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A",
                                java.util.Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);

                mockMvc.perform(get("/api/v1/auth/me")
                                .principal(authentication))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(200))
                                .andExpect(jsonPath("$.data.username").value("testuser"))
                                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                                .andExpect(jsonPath("$.data.locale").value("zh-CN"))
                                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                                .andExpect(jsonPath("$.data.lastLoginAt").value("2026-03-13T08:15:00"))
                                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

                verify(authService).getCurrentUserByUsername("testuser");
                SecurityContextHolder.clearContext();
        }

        @Test
        void getCurrentUser_NotAuthenticated_ThrowsBusinessException() throws Exception {
                SecurityContextHolder.clearContext();

                mockMvc.perform(get("/api/v1/auth/me"))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value(401))
                        .andExpect(jsonPath("$.message").value("Unauthorized access"));
        }

        @Test
        void getCurrentUser_AnonymousAuthentication_ThrowsBusinessException() throws Exception {
                Authentication authentication = new AnonymousAuthenticationToken(
                                "test-key",
                                "anonymousUser",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                mockMvc.perform(get("/api/v1/auth/me").principal(authentication))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value(401))
                                .andExpect(jsonPath("$.message").value("Unauthorized access"));

                SecurityContextHolder.clearContext();
        }

        private String validRegisterRequestBody() {
                return """
                                {
                                    "username": "testuser",
                                    "email": "test@example.com",
                                    "passwordEnvelope": {
                                        "keyId": "pwd-key-20260329",
                                        "nonce": "nonce-register-1",
                                        "timestamp": 1743211200000,
                                        "ciphertext": "ciphertext-register"
                                    },
                                    "inviteCode": "ABCD-WXYZ-2345"
                                }
                                """;
        }

        private String validLegacyRegisterRequestBody() {
                return """
                                {
                                    "username": "testuser",
                                    "email": "test@example.com",
                                    "password": "Password123",
                                    "inviteCode": "ABCD-WXYZ-2345"
                                }
                                """;
        }

        private String validLoginRequestBody() {
                return """
                                {
                                    "username": "testuser",
                                    "passwordEnvelope": {
                                        "keyId": "pwd-key-20260329",
                                        "nonce": "nonce-login-1",
                                        "timestamp": 1743211200000,
                                        "ciphertext": "ciphertext-login"
                                    }
                                }
                                """;
        }
}
