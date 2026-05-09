package com.josh.interviewj.billing.activation.controller;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.service.AdminAccessService;
import com.josh.interviewj.billing.activation.service.ActivationCodeFormatService;
import com.josh.interviewj.billing.activation.service.ActivationCodeQueryService;
import com.josh.interviewj.billing.activation.service.ActivationCodeService;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminActivationCodeValidationTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private ActivationCodeService activationCodeService;

    @Mock
    private ActivationCodeQueryService activationCodeQueryService;

    @Mock
    private ActivationCodeFormatService activationCodeFormatService;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private MockMvc mockMvc;
    private TestingAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        AdminActivationCodeController controller = new AdminActivationCodeController(
                adminAccessService,
                activationCodeService,
                activationCodeQueryService,
                activationCodeFormatService,
                runtimeSwitchService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        authentication = new TestingAuthenticationToken("admin", "n/a");

        User admin = User.builder().id(1L).username("admin").build();
        admin.addRole("ADMIN");
        lenient().when(adminAccessService.requireAdmin(any())).thenReturn(admin);
    }

    @Test
    void createSubscriptionMissingBillingPlanVersionIdReturnsFieldLevelError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/activation-codes")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {
                                  "codeType": "SUBSCRIPTION",
                                  "subscriptionDurationDays": 30
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.hasItem("billingPlanVersionId")))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("subscriptionFieldsValid"))));
    }

    @Test
    void createSubscriptionMissingDurationReturnsFieldLevelError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/activation-codes")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {
                                  "codeType": "SUBSCRIPTION",
                                  "billingPlanVersionId": 42
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.hasItem("subscriptionDurationDays")))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("subscriptionFieldsValid"))));
    }

    @Test
    void createCreditMissingAmountReturnsFieldLevelError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/activation-codes")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {
                                  "codeType": "CREDIT"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.hasItem("creditAmountMicros")))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("creditFieldsValid"))));
    }

    @Test
    void createBatchCountOutOfRangeStillReturnsCountField() throws Exception {
        mockMvc.perform(post("/api/v1/admin/activation-codes/batch")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {
                                  "count": 0,
                                  "codeType": "CREDIT",
                                  "creditAmountMicros": 1000000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.hasItem("count")));
    }

    @Test
    void createBatchSubscriptionMissingVersionReturnsFieldLevelError() throws Exception {
        mockMvc.perform(post("/api/v1/admin/activation-codes/batch")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("""
                                {
                                  "count": 2,
                                  "codeType": "SUBSCRIPTION",
                                  "subscriptionDurationDays": 7
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field").value(org.hamcrest.Matchers.hasItem("billingPlanVersionId")));
    }
}
