package com.josh.interviewj.controller;

import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.usage.controller.AdminUsageAuditController;
import com.josh.interviewj.usage.dto.response.AdminUsageEventResponse;
import com.josh.interviewj.usage.dto.response.AdminUsageSummaryResponse;
import com.josh.interviewj.usage.service.AdminUsageAuditQueryService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUsageAuditControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AdminUsageAuditQueryService adminUsageAuditQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUsageAuditController(adminAccessService, adminUsageAuditQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getUsageEvents_NonAdmin_Returns403() throws Exception {
        doThrow(new BusinessException("AUTH_006", "Forbidden"))
                .when(adminAccessService).requireAdmin(any());

        mockMvc.perform(get("/api/v1/admin/usage-events")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("AUTH_006"));
    }

    @Test
    void getUsageEvents_ReturnsCostAndCreditStatuses() throws Exception {
        when(adminUsageAuditQueryService.getUsageEvents(any())).thenReturn(new PageImpl<>(List.of(
                AdminUsageEventResponse.builder()
                        .id("evt-1")
                        .userId("user-1")
                        .usageFamily("CHAT")
                        .purpose("analysis")
                        .provider("dispatcher_rc")
                        .modelCode("gpt-5.4")
                        .resourceType("RESUME_ANALYSIS_REPORT")
                        .resourceExternalId("resume-1")
                        .operationId("op-1")
                        .promptTokens(100L)
                        .completionTokens(20L)
                        .totalTokens(120L)
                        .costChargeStatus("CHARGEABLE")
                        .creditChargeStatus("PENDING")
                        .chargeBucket("RESUME_CREDITS")
                        .createdAt(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                        .build()
        ), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/usage-events")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].costChargeStatus").value("CHARGEABLE"))
                .andExpect(jsonPath("$.data.content[0].creditChargeStatus").value("PENDING"));
    }

    @Test
    void getUsageSummary_ReturnsAggregatesAndUtcTimestamp() throws Exception {
        when(adminUsageAuditQueryService.getUsageSummary(any())).thenReturn(AdminUsageSummaryResponse.builder()
                .dimension("modelCode")
                .overall(AdminUsageSummaryResponse.Overall.builder()
                        .totalRecordedTokens(180000L)
                        .totalRequestCount(430L)
                        .pendingCostEventCount(12L)
                        .pendingCreditEventCount(4L)
                        .totalBilledAmount("12.340000")
                        .totalChargedCredits("150.000")
                        .totalChargedCreditsMicros(150000L)
                        .build())
                .rows(List.of())
                .build());

        mockMvc.perform(get("/api/v1/admin/usage-summary")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overall.pendingCostEventCount").value(12))
                .andExpect(jsonPath("$.data.overall.pendingCreditEventCount").value(4))
                .andExpect(jsonPath("$.timestamp", Matchers.matchesPattern(".*Z$")));
    }
}
