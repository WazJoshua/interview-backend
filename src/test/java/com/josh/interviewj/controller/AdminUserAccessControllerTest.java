package com.josh.interviewj.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.auth.controller.AdminUserAccessController;
import com.josh.interviewj.auth.dto.response.AdminUserRoleFlagsResponse;
import com.josh.interviewj.auth.service.AdminUserAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUserAccessControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AdminUserAccessService adminUserAccessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserAccessController(adminAccessService, adminUserAccessService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getRoleFlags_ReturnsCurrentRolesAndInviterFlag() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminUserAccessService.getUserRoleFlags(any())).thenReturn(AdminUserRoleFlagsResponse.builder()
                .userId("user-1")
                .roles(List.of("INVITER", "USER"))
                .flags(AdminUserRoleFlagsResponse.Flags.builder().inviter(true).build())
                .build());

        mockMvc.perform(get("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags.inviter").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("INVITER"));
    }

    @Test
    void updateRoleFlags_ReturnsUpdatedFlags() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminUserAccessService.updateUserRoleFlags(anyLong(), any(), any())).thenReturn(AdminUserRoleFlagsResponse.builder()
                .userId("user-1")
                .roles(List.of("USER"))
                .flags(AdminUserRoleFlagsResponse.Flags.builder().inviter(false).build())
                .build());

        mockMvc.perform(put("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inviter": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags.inviter").value(false))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
    }

    @Test
    void getRoleFlags_NonAdmin_Returns403() throws Exception {
        doThrow(new BusinessException(ErrorCode.AUTH_006, "Forbidden"))
                .when(adminAccessService).requireAdmin(any());

        mockMvc.perform(get("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.AUTH_006));
    }

    @Test
    void updateRoleFlags_UserNotFound_Returns404() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminUserAccessService.updateUserRoleFlags(anyLong(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.USER_003, "User not found"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inviter": true
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.USER_003));
    }

    @Test
    void updateRoleFlags_EmptyBody_Returns400ValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminUserAccessService);
    }

    @Test
    void updateRoleFlags_UnsupportedField_Returns400ValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{userId}/role-flags", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inviter": true,
                                  "vip": false
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminUserAccessService);
    }

    private User adminUser() {
        return User.builder()
                .id(1L)
                .externalId(UUID.randomUUID())
                .username("admin")
                .email("admin@example.com")
                .password("hashed")
                .build();
    }
}
