package com.josh.interviewj.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.usage.controller.AdminCreditsBillingController;
import com.josh.interviewj.usage.dto.response.AdminCreditPolicyVersionResponse;
import com.josh.interviewj.usage.dto.response.AdminUserCreditPolicyResponse;
import com.josh.interviewj.usage.service.AdminCreditsBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminCreditsBillingControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AdminCreditsBillingService adminCreditsBillingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminCreditsBillingController(adminAccessService, adminCreditsBillingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getPolicyVersions_NonAdmin_Returns403() throws Exception {
        doThrow(new BusinessException("AUTH_006", "Forbidden"))
                .when(adminAccessService).requireAdmin(any());

        mockMvc.perform(get("/api/v1/admin/credits/policy-versions")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPolicyVersion_InvalidCombination_Returns422() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminCreditsBillingService.createPolicyVersion(anyLong(), any()))
                .thenThrow(new BusinessException("ADMIN_CREDIT_001", "TOKEN_AND_REQUEST billing requires both token and request ratios"));

        mockMvc.perform(post("/api/v1/admin/credits/policy-versions")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "kb_query_embedding",
                                  "chargeBucket": "KB_QUERY_CREDITS",
                                  "usageFamily": "EMBEDDING",
                                  "effectiveFrom": "2026-04-01T00:00:00Z",
                                  "billingUnit": "TOKEN_AND_REQUEST",
                                  "requestRatio": "0.250"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value("ADMIN_CREDIT_001"));
    }

    @Test
    void createPolicyVersion_WhenEffectiveRangeInvalid_Returns400ValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/credits/policy-versions")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "purpose": "kb_query_embedding",
                                  "chargeBucket": "KB_QUERY_CREDITS",
                                  "usageFamily": "EMBEDDING",
                                  "effectiveFrom": "2026-04-01T00:00:00Z",
                                  "effectiveTo": "2026-04-01T00:00:00Z",
                                  "billingUnit": "REQUEST",
                                  "requestRatio": "0.250"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminCreditsBillingService);
    }

    @Test
    void getPolicyVersions_ReturnsPurposeChargeBucketAndUsageFamily() throws Exception {
        when(adminCreditsBillingService.getPolicyVersions(any())).thenReturn(new PageImpl<>(List.of(
                AdminCreditPolicyVersionResponse.builder()
                        .id("1")
                        .purpose("kb_query_rerank")
                        .chargeBucket("KB_QUERY_CREDITS")
                        .usageFamily("RERANK")
                        .effectiveFrom(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                        .billingUnit("REQUEST")
                        .requestRatio("0.500")
                        .build()
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/credits/policy-versions")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].purpose").value("kb_query_rerank"))
                .andExpect(jsonPath("$.data.content[0].chargeBucket").value("KB_QUERY_CREDITS"))
                .andExpect(jsonPath("$.data.content[0].usageFamily").value("RERANK"));
    }

    @Test
    void getUserCreditPolicy_NoPolicy_Returns409() throws Exception {
        when(adminCreditsBillingService.getUserCreditPolicy(any()))
                .thenThrow(new BusinessException("ADMIN_CREDIT_003", "Target user credit policy is not configured"));

        mockMvc.perform(get("/api/v1/admin/users/{userId}/credit-policy", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("ADMIN_CREDIT_003"));
    }

    @Test
    void updateUserCreditPolicy_ReturnsActiveAndPendingPolicy() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(adminCreditsBillingService.updateUserCreditPolicy(anyLong(), any(), any())).thenReturn(AdminUserCreditPolicyResponse.builder()
                .userId("user-1")
                .activePolicy(AdminUserCreditPolicyResponse.Policy.builder().id("1").resumeCreditsLimit("200.000").build())
                .pendingPolicy(AdminUserCreditPolicyResponse.Policy.builder().id("2").resumeCreditsLimit("240.000").build())
                .currentPeriod(AdminUserCreditPolicyResponse.CurrentPeriod.builder().periodType("MONTHLY").build())
                .build());

        mockMvc.perform(put("/api/v1/admin/users/{userId}/credit-policy", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-04-01T00:00:00Z",
                                  "resumeCreditsLimitMicros": 240000,
                                  "kbQueryCreditsLimitMicros": 320000,
                                  "kbIngestionCreditsLimitMicros": 1000000,
                                  "interviewCreditsLimitMicros": 240000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activePolicy.id").value("1"))
                .andExpect(jsonPath("$.data.pendingPolicy.id").value("2"));
    }

    @Test
    void updateUserCreditPolicy_WhenEffectiveRangeInvalid_Returns400ValidationError() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{userId}/credit-policy", UUID.randomUUID())
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "effectiveFrom": "2026-04-01T00:00:00Z",
                                  "effectiveTo": "2026-04-01T00:00:00Z",
                                  "resumeCreditsLimitMicros": 240000,
                                  "kbQueryCreditsLimitMicros": 320000,
                                  "kbIngestionCreditsLimitMicros": 1000000,
                                  "interviewCreditsLimitMicros": 240000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"));

        verifyNoInteractions(adminCreditsBillingService);
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
