package com.josh.interviewj.controller;

import com.josh.interviewj.admin.model.AdminOperationResourceType;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.admin.controller.AdminRuntimeSwitchController;
import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.api.RequestIdFilter;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesAdminView;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesSnapshot;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminRuntimeSwitchControllerTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    @Mock
    private AdminOperationLogService adminOperationLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminRuntimeSwitchController(
                                adminAccessService,
                                runtimeSwitchService,
                                adminOperationLogService
                        )
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void getRuntimeSwitches_AdminCanReadLatestAggregatedState() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(runtimeSwitchService.getAdminView()).thenReturn(adminView(true, 12L, false, true));

        mockMvc.perform(get("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.revision").value(12))
                .andExpect(jsonPath("$.data.payment.enabled").value(false))
                .andExpect(jsonPath("$.data.activationCode.enabled").value(true));
    }

    @Test
    void getRuntimeSwitches_NonAdminRejected() throws Exception {
        when(adminAccessService.requireAdmin(any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_006, "Forbidden"));

        mockMvc.perform(get("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.AUTH_006));
    }

    @Test
    void updateRuntimeSwitches_NonAdminRejected() throws Exception {
        when(adminAccessService.requireAdmin(any()))
                .thenThrow(new BusinessException(ErrorCode.AUTH_006, "Forbidden"));

        mockMvc.perform(put("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("user", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 5,
                                  "paymentEnabled": false,
                                  "paymentDisabledMessage": "payment closed",
                                  "activationCodeEnabled": false,
                                  "activationCodeDisabledMessage": "activation closed"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.AUTH_006));

        verifyNoInteractions(runtimeSwitchService);
        verifyNoInteractions(adminOperationLogService);
    }

    @Test
    void updateRuntimeSwitches_AdminCanUpdate_AndAuditUsesSameRequestIdAsResponse() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(runtimeSwitchService.getAdminView()).thenReturn(adminView(true, 5L, true, true));
        when(runtimeSwitchService.updateSwitches(any(), eq(1L))).thenReturn(snapshot(true, 6L, false, false));

        mockMvc.perform(put("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .header("X-Request-Id", "req_rt_admin_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 5,
                                  "paymentEnabled": false,
                                  "paymentDisabledMessage": "payment closed",
                                  "activationCodeEnabled": false,
                                  "activationCodeDisabledMessage": "activation closed"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req_rt_admin_001"))
                .andExpect(jsonPath("$.data.revision").value(6))
                .andExpect(jsonPath("$.data.payment.enabled").value(false))
                .andExpect(jsonPath("$.data.activationCode.enabled").value(false));

        ArgumentCaptor<String> requestIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(adminOperationLogService).recordUpdate(
                eq(1L),
                eq(AdminOperationResourceType.SYSTEM_SETTING),
                eq("runtime-switches"),
                requestIdCaptor.capture(),
                any(),
                any(),
                any()
        );
        assertThat(requestIdCaptor.getValue()).isEqualTo("req_rt_admin_001");
    }

    @Test
    void updateRuntimeSwitches_WhenExpectedRevisionIsStale_Returns409Conflict() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(runtimeSwitchService.getAdminView()).thenReturn(adminView(true, 5L, true, true));
        when(runtimeSwitchService.updateSwitches(any(), eq(1L)))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_BILLING_005, "Runtime switches revision conflict"));

        mockMvc.perform(put("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevision": 4,
                                  "paymentEnabled": false,
                                  "paymentDisabledMessage": "payment closed",
                                  "activationCodeEnabled": false,
                                  "activationCodeDisabledMessage": "activation closed"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.ADMIN_BILLING_005));

        verifyNoInteractions(adminOperationLogService);
    }

    @Test
    void getRuntimeSwitches_ReadyFalseMaintainsStableStructure() throws Exception {
        when(adminAccessService.requireAdmin(any())).thenReturn(adminUser());
        when(runtimeSwitchService.getAdminView()).thenReturn(adminView(false, null, true, true));

        mockMvc.perform(get("/api/v1/admin/runtime-switches")
                        .principal(new UsernamePasswordAuthenticationToken("admin", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.revision").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.payment.enabled").value(true))
                .andExpect(jsonPath("$.data.activationCode.enabled").value(true));
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

    private RuntimeSwitchesAdminView adminView(
            boolean ready,
            Long revision,
            boolean paymentEnabled,
            boolean activationEnabled
    ) {
        return RuntimeSwitchesAdminView.builder()
                .ready(ready)
                .revision(revision)
                .payment(RuntimeSwitchesAdminView.CapabilityView.builder()
                        .enabled(paymentEnabled)
                        .disabledMessage("payment disabled")
                        .build())
                .activationCode(RuntimeSwitchesAdminView.CapabilityView.builder()
                        .enabled(activationEnabled)
                        .disabledMessage("activation disabled")
                        .build())
                .build();
    }

    private RuntimeSwitchesSnapshot snapshot(
            boolean ready,
            Long revision,
            boolean paymentEnabled,
            boolean activationEnabled
    ) {
        return RuntimeSwitchesSnapshot.builder()
                .ready(ready)
                .revision(revision)
                .payment(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(paymentEnabled)
                        .disabledMessage("payment disabled")
                        .build())
                .activationCode(RuntimeSwitchesSnapshot.CapabilitySnapshot.builder()
                        .enabled(activationEnabled)
                        .disabledMessage("activation disabled")
                        .build())
                .build();
    }
}
