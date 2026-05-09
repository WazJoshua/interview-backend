package com.josh.interviewj.controller;

import com.josh.interviewj.billing.dto.response.UserBillingEntitlementsResponse;
import com.josh.interviewj.billing.dto.response.UserBillingLedgerItemResponse;
import com.josh.interviewj.billing.dto.response.UserBillingOrderResponse;
import com.josh.interviewj.billing.dto.response.StartBillingOrderPaymentResponse;
import com.josh.interviewj.billing.dto.response.UserBillingSubscriptionResponse;
import com.josh.interviewj.billing.service.BillingEntitlementQueryService;
import com.josh.interviewj.billing.service.BillingOrderService;
import com.josh.interviewj.billing.service.BillingPaymentService;
import com.josh.interviewj.billing.service.BillingRefundService;
import com.josh.interviewj.billing.service.PaymentOrderLifecycleService;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.common.exception.GlobalExceptionHandler;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.user.controller.CurrentUserBillingController;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CurrentUserBillingControllerTest {

    @Mock
    private BillingOrderService billingOrderService;

    @Mock
    private BillingEntitlementQueryService billingEntitlementQueryService;

    @Mock
    private PaymentOrderLifecycleService paymentOrderLifecycleService;

    @Mock
    private BillingPaymentService billingPaymentService;

    @Mock
    private BillingRefundService billingRefundService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CurrentUserBillingController(
                        billingOrderService,
                        billingEntitlementQueryService,
                        paymentOrderLifecycleService,
                        billingPaymentService,
                        billingRefundService
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createOrder_ReturnsFrozenOrderPayload() throws Exception {
        when(billingOrderService.createOrder(
                eq("josh"),
                any(com.josh.interviewj.billing.dto.request.CreateBillingOrderRequest.class)
        )).thenReturn(UserBillingOrderResponse.builder()
                .orderNo("po_123")
                .status("CREATED")
                .provider("mockpay")
                .orderType("SUBSCRIPTION_PURCHASE")
                .amount("29.900000")
                .currency("USD")
                .build());

        mockMvc.perform(post("/api/v1/billing/orders")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderType": "SUBSCRIPTION_PURCHASE",
                                  "provider": "mockpay",
                                  "idempotencyKey": "idem-1",
                                  "planCode": "plus"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("po_123"))
                .andExpect(jsonPath("$.data.orderType").value("SUBSCRIPTION_PURCHASE"));
    }

    @Test
    void createOrder_WhenPaymentDisabled_ReturnsConflict() throws Exception {
        when(billingOrderService.createOrder(
                eq("josh"),
                any(com.josh.interviewj.billing.dto.request.CreateBillingOrderRequest.class)
        )).thenThrow(new BusinessException(ErrorCode.PAYMENT_006, "Payment temporarily disabled"));

        mockMvc.perform(post("/api/v1/billing/orders")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderType": "SUBSCRIPTION_PURCHASE",
                                  "provider": "mockpay",
                                  "idempotencyKey": "idem-disabled",
                                  "planCode": "plus"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.PAYMENT_006));
    }

    @Test
    void getEntitlements_ReturnsSubscriptionAndDisplayTotal() throws Exception {
        when(billingEntitlementQueryService.getCurrentUserEntitlements("josh")).thenReturn(UserBillingEntitlementsResponse.builder()
                .subscription(UserBillingSubscriptionResponse.builder().status("ACTIVE").planCode("plus").tierCode("plus").build())
                .subscriptionBuckets(List.of(UserBillingEntitlementsResponse.Bucket.builder()
                        .bucketCode("RESUME_CREDITS")
                        .remainingAmountMicros(400_000L)
                        .remainingAmount("400.000")
                        .build()))
                .availablePurchasedBalanceMicros(100_000L)
                .displayTotalCreditsMicros(500_000L)
                .displayTotalCredits("500.000")
                .build());

        mockMvc.perform(get("/api/v1/billing/entitlements")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.displayTotalCredits").value("500.000"));
    }

    @Test
    void getLedger_ReturnsPageWrappedApiResponse() throws Exception {
        when(billingEntitlementQueryService.getCurrentUserLedger("josh", 1, 5))
                .thenReturn(new PageImpl<>(
                        List.of(UserBillingLedgerItemResponse.builder()
                                .eventType("CREDIT_PURCHASE_GRANTED")
                                .deltaAmountMicros(100_000L)
                                .build()),
                        PageRequest.of(1, 5),
                        11
                ));

        mockMvc.perform(get("/api/v1/billing/ledger")
                        .param("page", "1")
                        .param("size", "5")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].eventType").value("CREDIT_PURCHASE_GRANTED"))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(11));

        verify(billingEntitlementQueryService).getCurrentUserLedger("josh", 1, 5);
    }

    @Test
    void getActiveOrders_ReturnsCurrentUsersBlockingOrders() throws Exception {
        when(billingOrderService.getCurrentUserActiveOrders("josh")).thenReturn(List.of(
                UserBillingOrderResponse.builder()
                        .orderNo("po_active_1")
                        .status("CREATED")
                        .provider("mockpay")
                        .orderType("CREDIT_PURCHASE")
                        .reused(false)
                        .amount("9.900000")
                        .currency("USD")
                        .build()
        ));

        mockMvc.perform(get("/api/v1/billing/orders")
                        .param("status", "active")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderNo").value("po_active_1"));
    }

    @Test
    void cancelOrder_ReturnsUpdatedOrderResponse() throws Exception {
        PaymentOrder canceledOrder = PaymentOrder.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .orderNo("po_123")
                .userId(101L)
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .bizRefType("CREDIT_PURCHASE_SKU")
                .bizRefId("credits-basic")
                .provider("mockpay")
                .amount(new BigDecimal("9.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CANCELED)
                .idempotencyKey("idem-cancel")
                .providerOrderRef("po_123")
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\"}")
                .entitlementSnapshot("[]")
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 4, 1, 0, 1))
                .build();
        when(paymentOrderLifecycleService.cancelCurrentUserOrder(eq("josh"), eq("po_123"), eq("changed my mind")))
                .thenReturn(canceledOrder);
        when(billingOrderService.toUserBillingOrderResponse(canceledOrder)).thenReturn(UserBillingOrderResponse.builder()
                .orderNo("po_123")
                .status("CANCELED")
                .provider("mockpay")
                .orderType("CREDIT_PURCHASE")
                .amount("9.900000")
                .currency("USD")
                .build());

        mockMvc.perform(post("/api/v1/billing/orders/po_123/cancel")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "changed my mind"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("po_123"))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void startPayment_ReturnsApiResponse() throws Exception {
        when(billingPaymentService.startPayment(
                eq("josh"),
                eq("po_123"),
                any(com.josh.interviewj.billing.dto.request.StartBillingOrderPaymentRequest.class)
        )).thenReturn(new StartBillingOrderPaymentResponse(
                "po_123",
                "PENDING_PROVIDER",
                "https://openapi.alipay.com/gateway.do?pay"
        ));

        mockMvc.perform(post("/api/v1/billing/orders/po_123/pay")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "terminal": "PC_WEB",
                                  "returnUrl": "https://app.example.com/payments/result"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value("po_123"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PROVIDER"))
                .andExpect(jsonPath("$.data.redirectUrl").value("https://openapi.alipay.com/gateway.do?pay"));
    }

    @Test
    void startPayment_WhenPaymentDisabled_ReturnsConflict() throws Exception {
        when(billingPaymentService.startPayment(
                eq("josh"),
                eq("po_123"),
                any(com.josh.interviewj.billing.dto.request.StartBillingOrderPaymentRequest.class)
        )).thenThrow(new BusinessException(ErrorCode.PAYMENT_006, "Payment temporarily disabled"));

        mockMvc.perform(post("/api/v1/billing/orders/po_123/pay")
                        .principal(new UsernamePasswordAuthenticationToken("josh", "n/a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "terminal": "PC_WEB",
                                  "returnUrl": "https://app.example.com/payments/result"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value(ErrorCode.PAYMENT_006));
    }
}
