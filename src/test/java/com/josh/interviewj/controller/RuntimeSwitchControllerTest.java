package com.josh.interviewj.controller;

import com.josh.interviewj.common.api.RequestIdFilter;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.common.settings.controller.RuntimeSwitchController;
import com.josh.interviewj.common.settings.dto.RuntimeSwitchesPublicView;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RuntimeSwitchControllerTest {

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RuntimeSwitchController(runtimeSwitchService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void getRuntimeSwitches_AnonymousCanReadCachedSnapshot() throws Exception {
        when(runtimeSwitchService.getPublicView()).thenReturn(publicView(true, 18L, false, true));

        mockMvc.perform(get("/api/v1/system/runtime-switches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(true))
                .andExpect(jsonPath("$.data.revision").value(18))
                .andExpect(jsonPath("$.data.payment.enabled").value(false))
                .andExpect(jsonPath("$.data.activationCode.enabled").value(true));
    }

    @Test
    void getRuntimeSwitches_ReadyFalseReturnsStableStructureAndNullRevision() throws Exception {
        when(runtimeSwitchService.getPublicView()).thenReturn(publicView(false, null, true, true));

        mockMvc.perform(get("/api/v1/system/runtime-switches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.revision").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.payment.enabled").value(true))
                .andExpect(jsonPath("$.data.payment.disabledMessage").value("payment disabled"))
                .andExpect(jsonPath("$.data.activationCode.enabled").value(true))
                .andExpect(jsonPath("$.data.activationCode.disabledMessage").value("activation disabled"));
    }

    private RuntimeSwitchesPublicView publicView(
            boolean ready,
            Long revision,
            boolean paymentEnabled,
            boolean activationEnabled
    ) {
        return RuntimeSwitchesPublicView.builder()
                .ready(ready)
                .revision(revision)
                .payment(RuntimeSwitchesPublicView.CapabilityView.builder()
                        .enabled(paymentEnabled)
                        .disabledMessage("payment disabled")
                        .build())
                .activationCode(RuntimeSwitchesPublicView.CapabilityView.builder()
                        .enabled(activationEnabled)
                        .disabledMessage("activation disabled")
                        .build())
                .build();
    }
}
