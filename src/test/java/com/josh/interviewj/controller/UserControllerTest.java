package com.josh.interviewj.controller;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.user.controller.UserController;
import com.josh.interviewj.user.dto.request.ChangePasswordRequest;
import com.josh.interviewj.user.dto.request.UserUpdateRequest;
import com.josh.interviewj.user.dto.response.AvatarUploadResponse;
import com.josh.interviewj.user.dto.response.UserOverviewResponse;
import com.josh.interviewj.user.dto.response.UserProfileResponse;
import com.josh.interviewj.user.service.UserOverviewQueryService;
import com.josh.interviewj.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserOverviewQueryService userOverviewQueryService;

    @InjectMocks
    private UserController userController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JsonNullableModule())
            .build();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter((tools.jackson.databind.json.JsonMapper) objectMapper))
                .build();
    }

    @Test
    void getProfile_SelfAccess_ReturnsProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userService.getProfile(userId, "testuser"))
                .thenReturn(UserProfileResponse.builder()
                        .id(userId)
                        .username("testuser")
                        .email("test@example.com")
                        .nickname("测试用户")
                        .avatarUrl("uploads/avatars/avatar.png")
                        .phone("13800138000")
                        .locale("zh-CN")
                        .timezone("Asia/Shanghai")
                        .createdAt(LocalDateTime.of(2026, 3, 13, 9, 30))
                        .build());

        mockMvc.perform(get("/api/v1/users/{userId}", userId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.locale").value("zh-CN"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"));
    }

    @Test
    void getOverview_SelfAccess_ReturnsOverview() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userOverviewQueryService.getOverview(userId, "testuser"))
                .thenReturn(UserOverviewResponse.builder()
                        .resumeAverageScore(new java.math.BigDecimal("84.50"))
                        .interviewAverageScore(new java.math.BigDecimal("78.25"))
                        .mockInterviewCompletedCount(6L)
                        .recentActivity(UserOverviewResponse.RecentActivity.builder()
                                .latestInterview(UserOverviewResponse.LatestInterview.builder()
                                        .interviewId(UUID.randomUUID())
                                        .status("COMPLETED")
                                        .reportStatus("READY")
                                        .score(new java.math.BigDecimal("81.50"))
                                        .occurredAt(LocalDateTime.of(2026, 3, 30, 10, 15))
                                        .build())
                                .latestResume(UserOverviewResponse.LatestResume.builder()
                                        .resumeId(UUID.randomUUID())
                                        .fileName("resume.pdf")
                                        .uploadStatus("PARSED")
                                        .uploadedAt(LocalDateTime.of(2026, 3, 29, 18, 0))
                                        .parsed(true)
                                        .parsedAt(LocalDateTime.of(2026, 3, 29, 18, 2, 10))
                                        .analysisStatus("COMPLETED")
                                        .analyzed(true)
                                        .analysisAt(LocalDateTime.of(2026, 3, 29, 18, 5, 40))
                                        .build())
                                .latestKnowledgeBaseQuestion(UserOverviewResponse.LatestKnowledgeBaseQuestion.builder()
                                        .kbId(UUID.randomUUID())
                                        .kbName("Java KB")
                                        .question("Explain optimistic locking")
                                        .askedAt(LocalDateTime.of(2026, 3, 30, 9, 40))
                                        .build())
                                .build())
                        .build());

        mockMvc.perform(get("/api/v1/users/{userId}/overview", userId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resumeAverageScore").value(84.5))
                .andExpect(jsonPath("$.data.interviewAverageScore").value(78.25))
                .andExpect(jsonPath("$.data.mockInterviewCompletedCount").value(6))
                .andExpect(jsonPath("$.data.recentActivity.latestInterview.reportStatus").value("READY"))
                .andExpect(jsonPath("$.data.recentActivity.latestResume.fileName").value("resume.pdf"))
                .andExpect(jsonPath("$.data.recentActivity.latestKnowledgeBaseQuestion.kbName").value("Java KB"));
    }

    @Test
    void getOverview_AdminAccess_ReturnsOverview() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "N/A");
        when(userOverviewQueryService.getOverview(userId, "admin"))
                .thenReturn(UserOverviewResponse.builder()
                        .resumeAverageScore(java.math.BigDecimal.ZERO)
                        .interviewAverageScore(java.math.BigDecimal.ZERO)
                        .mockInterviewCompletedCount(0L)
                        .recentActivity(UserOverviewResponse.RecentActivity.builder().build())
                        .build());

        mockMvc.perform(get("/api/v1/users/{userId}/overview", userId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mockInterviewCompletedCount").value(0));
    }

    @Test
    void getOverview_OtherUser_Returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userOverviewQueryService.getOverview(userId, "testuser"))
                .thenThrow(new BusinessException("USER_003", "User not found"));

        mockMvc.perform(get("/api/v1/users/{userId}/overview", userId).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void getOverview_EmptyRecentActivity_SerializesNullBranches() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userOverviewQueryService.getOverview(userId, "testuser"))
                .thenReturn(UserOverviewResponse.builder()
                        .resumeAverageScore(java.math.BigDecimal.ZERO)
                        .interviewAverageScore(java.math.BigDecimal.ZERO)
                        .mockInterviewCompletedCount(0L)
                        .recentActivity(UserOverviewResponse.RecentActivity.builder()
                                .latestInterview(null)
                                .latestResume(null)
                                .latestKnowledgeBaseQuestion(null)
                                .build())
                        .build());

        mockMvc.perform(get("/api/v1/users/{userId}/overview", userId).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recentActivity.latestInterview").value(nullValue()))
                .andExpect(jsonPath("$.data.recentActivity.latestResume").value(nullValue()))
                .andExpect(jsonPath("$.data.recentActivity.latestKnowledgeBaseQuestion").value(nullValue()));
    }

    @Test
    void patchProfile_SelfAccess_ReturnsUpdatedProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userService.updateProfile(eq(userId), eq("testuser"), any(UserUpdateRequest.class)))
                .thenReturn(UserProfileResponse.builder()
                        .id(userId)
                        .username("testuser")
                        .email("test@example.com")
                        .nickname("三哥")
                        .phone("13900139000")
                        .locale("en-US")
                        .timezone("America/New_York")
                        .createdAt(LocalDateTime.of(2026, 3, 13, 9, 30))
                        .build());

        String requestBody = """
                {
                  "nickname": "三哥",
                  "phone": "13900139000",
                  "locale": "en-US",
                  "timezone": "America/New_York"
                }
                """;

        mockMvc.perform(patch("/api/v1/users/{userId}", userId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("三哥"))
                .andExpect(jsonPath("$.data.locale").value("en-US"))
                .andExpect(jsonPath("$.data.timezone").value("America/New_York"));
    }

    @Test
    void getProfile_OtherUser_Returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userService.getProfile(userId, "testuser"))
                .thenThrow(new BusinessException("USER_003", "User not found"));

        mockMvc.perform(get("/api/v1/users/{userId}", userId).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void patchProfile_OtherUser_Returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        when(userService.updateProfile(eq(userId), eq("testuser"), any(UserUpdateRequest.class)))
                .thenThrow(new BusinessException("USER_003", "User not found"));

        mockMvc.perform(patch("/api/v1/users/{userId}", userId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"三哥\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void changePassword_OtherUser_Returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        doThrow(new BusinessException("USER_003", "User not found"))
                .when(userService).changePassword(eq(userId), eq("testuser"), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/v1/users/{userId}/password", userId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "oldPasswordEnvelope": {
                                    "keyId": "pwd-key-20260329",
                                    "nonce": "nonce-old-password",
                                    "timestamp": 1743211200000,
                                    "ciphertext": "ciphertext-old-password"
                                  },
                                  "newPasswordEnvelope": {
                                    "keyId": "pwd-key-20260329",
                                    "nonce": "nonce-new-password",
                                    "timestamp": 1743211200000,
                                    "ciphertext": "ciphertext-new-password"
                                  }
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void changePassword_SelfAccess_Returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        doNothing().when(userService).changePassword(eq(userId), eq("testuser"), any(ChangePasswordRequest.class));

        mockMvc.perform(put("/api/v1/users/{userId}/password", userId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "oldPasswordEnvelope": {
                                    "keyId": "pwd-key-20260329",
                                    "nonce": "nonce-old-password",
                                    "timestamp": 1743211200000,
                                    "ciphertext": "ciphertext-old-password"
                                  },
                                  "newPasswordEnvelope": {
                                    "keyId": "pwd-key-20260329",
                                    "nonce": "nonce-new-password",
                                    "timestamp": 1743211200000,
                                    "ciphertext": "ciphertext-new-password"
                                  }
                                }
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void changePassword_PlaintextPasswordsRejected_ReturnsBadRequest() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");

        mockMvc.perform(put("/api/v1/users/{userId}/password", userId)
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "oldPassword": "OldPass123!",
                                  "newPassword": "NewPass456!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));
    }

    @Test
    void uploadAvatar_OtherUser_Returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "img".getBytes());
        when(userService.uploadAvatar(eq(userId), eq("testuser"), any(MockMultipartFile.class)))
                .thenThrow(new BusinessException("USER_003", "User not found"));

        mockMvc.perform(multipart("/api/v1/users/{userId}/avatar", userId)
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("USER_003"));
    }

    @Test
    void uploadAvatar_SelfAccess_ReturnsAvatarUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "img".getBytes());
        when(userService.uploadAvatar(eq(userId), eq("testuser"), any(MockMultipartFile.class)))
                .thenReturn(AvatarUploadResponse.builder()
                        .avatarUrl("uploads/avatars/avatar.png")
                        .build());

        mockMvc.perform(multipart("/api/v1/users/{userId}/avatar", userId)
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("uploads/avatars/avatar.png"));
    }

    @Test
    void uploadAvatar_InvalidFileType_Returns400() throws Exception {
        UUID userId = UUID.randomUUID();
        Authentication authentication = new UsernamePasswordAuthenticationToken("testuser", "N/A");
        MockMultipartFile file = new MockMultipartFile("file", "avatar.gif", "image/gif", "img".getBytes());
        when(userService.uploadAvatar(eq(userId), eq("testuser"), any(MockMultipartFile.class)))
                .thenThrow(new BusinessException("USER_004", "Avatar file type is not supported"));

        mockMvc.perform(multipart("/api/v1/users/{userId}/avatar", userId)
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Avatar file type is not supported"));
    }
}
