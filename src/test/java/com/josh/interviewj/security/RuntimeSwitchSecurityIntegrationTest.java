package com.josh.interviewj.security;

import com.josh.interviewj.admin.controller.AdminRuntimeSwitchController;
import com.josh.interviewj.admin.service.AdminOperationLogService;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.common.settings.controller.RuntimeSwitchController;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesPublicView;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import com.josh.interviewj.config.SecurityConfig;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        RuntimeSwitchController.class,
        AdminRuntimeSwitchController.class
})
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class RuntimeSwitchSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeSwitchService runtimeSwitchService;

    @MockitoBean
    private AdminAccessService adminAccessService;

    @MockitoBean
    private AdminOperationLogService adminOperationLogService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            Object request = invocation.getArgument(0);
            Object response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter((jakarta.servlet.ServletRequest) request, (jakarta.servlet.ServletResponse) response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void anonymousUser_CanAccessPublicRuntimeSwitchEndpoint() throws Exception {
        when(runtimeSwitchService.getPublicView()).thenReturn(publicView());

        mockMvc.perform(get("/api/v1/system/runtime-switches")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.revision").value(7));
    }

    @Test
    void anonymousUser_CannotAccessAdminRuntimeSwitchEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/admin/runtime-switches"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    private RuntimeSwitchesPublicView publicView() {
        return RuntimeSwitchesPublicView.builder()
                .ready(true)
                .revision(7L)
                .payment(RuntimeSwitchesPublicView.CapabilityView.builder()
                        .enabled(true)
                        .disabledMessage("payment disabled")
                        .build())
                .activationCode(RuntimeSwitchesPublicView.CapabilityView.builder()
                        .enabled(true)
                        .disabledMessage("activation disabled")
                        .build())
                .build();
    }
}
