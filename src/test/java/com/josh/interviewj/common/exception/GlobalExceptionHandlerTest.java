package com.josh.interviewj.common.exception;

import com.josh.interviewj.common.api.ApiResponse;
import com.josh.interviewj.common.api.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void handleBusinessException_MapsPaymentErrorsToStableHttpStatus() throws Exception {
        mockMvc.perform(get("/test/payment-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.PAYMENT_002));

        mockMvc.perform(get("/test/payment-conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.PAYMENT_003));
    }

    @Test
    void handleBusinessException_MapsUserAndAdminBillingErrorsToStableHttpStatus() throws Exception {
        mockMvc.perform(get("/test/user-billing-conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.USER_BILLING_001));

        mockMvc.perform(get("/test/admin-billing-invalid").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.ADMIN_BILLING_001));

        mockMvc.perform(get("/test/admin-billing-revision-conflict").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.ADMIN_BILLING_005));

        mockMvc.perform(get("/test/activation-code-disabled").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.BILLING_ACTIVATION_004));
    }

    @Test
    void requestId_IsStableForSuccessAndExceptionResponsesWithinRequestChain() throws Exception {
        mockMvc.perform(get("/test/success")
                        .header("X-Request-Id", "req_chain_001")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req_chain_001"));

        mockMvc.perform(get("/test/payment-not-found")
                        .header("X-Request-Id", "req_chain_002")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("req_chain_002"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/payment-not-found")
        ResponseEntity<Void> paymentNotFound() {
            throw new BusinessException(ErrorCode.PAYMENT_002, "Payment order not found");
        }

        @GetMapping("/test/payment-conflict")
        ResponseEntity<Void> paymentConflict() {
            throw new BusinessException(ErrorCode.PAYMENT_003, "Payment order expired");
        }

        @GetMapping("/test/user-billing-conflict")
        ResponseEntity<Void> userBillingConflict() {
            throw new BusinessException(ErrorCode.USER_BILLING_001, "Insufficient billing balance");
        }

        @GetMapping("/test/admin-billing-invalid")
        ResponseEntity<Void> adminBillingInvalid() {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_001, "Unsupported entitlement bucket");
        }

        @GetMapping("/test/admin-billing-revision-conflict")
        ResponseEntity<Void> adminBillingRevisionConflict() {
            throw new BusinessException(ErrorCode.ADMIN_BILLING_005, "Runtime switches revision conflict");
        }

        @GetMapping("/test/activation-code-disabled")
        ResponseEntity<Void> activationCodeDisabled() {
            throw new BusinessException(ErrorCode.BILLING_ACTIVATION_004, "Activation code capability disabled");
        }

        @GetMapping("/test/success")
        ApiResponse<String> success() {
            return ApiResponse.success("ok");
        }
    }
}
