package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.dto.request.StartBillingOrderPaymentRequest;
import com.josh.interviewj.billing.dto.response.StartBillingOrderPaymentResponse;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentInitiationResult;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.common.settings.service.RuntimeSwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingPaymentServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentProviderRegistry paymentProviderRegistry;

    @Mock
    private PaymentProviderAdapter paymentProviderAdapter;

    @Mock
    private RuntimeSwitchService runtimeSwitchService;

    private BillingPaymentService service;

    @BeforeEach
    void setUp() {
        BillingProperties billingProperties = new BillingProperties();
        billingProperties.getOrder().setDefaultExpireMinutes(30);
        service = new BillingPaymentService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                billingProperties,
                userRepository,
                paymentOrderRepository,
                paymentProviderRegistry,
                runtimeSwitchService
        );
        lenient().when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));
        lenient().when(paymentProviderRegistry.requireAdapter("alipay")).thenReturn(paymentProviderAdapter);
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void startPayment_WhenOrderIsCreatable_ReturnsRedirectUrlAndMarksPendingProvider() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.CREATED);
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(order));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentProviderAdapter.initiatePayment(any())).thenReturn(new PaymentInitiationResult(
                "po_123",
                "https://openapi.alipay.com/gateway.do?pay"
        ));

        StartBillingOrderPaymentResponse response = service.startPayment("josh", "po_123", paymentRequest("PC_WEB"));

        assertThat(response.orderNo()).isEqualTo("po_123");
        assertThat(response.status()).isEqualTo("PENDING_PROVIDER");
        assertThat(response.redirectUrl()).contains("gateway.do");
        verify(paymentOrderRepository).save(order);
    }

    @Test
    void startPayment_WhenOrderAlreadyPendingProvider_ReturnsFreshRedirectUrlWithoutCreatingNewOrder() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.PENDING_PROVIDER);
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(order));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentProviderAdapter.initiatePayment(any())).thenReturn(new PaymentInitiationResult(
                "po_123",
                "https://openapi.alipay.com/gateway.do?retry"
        ));

        StartBillingOrderPaymentResponse response = service.startPayment("josh", "po_123", paymentRequest("PC_WEB"));

        assertThat(response.redirectUrl()).contains("retry");
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.PENDING_PROVIDER);
        verify(paymentOrderRepository).save(order);
    }

    @Test
    void startPayment_WhenProviderReturnsBlankOrderRef_KeepsExistingOrderRef() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.CREATED);
        order.setProviderOrderRef("existing-ref");
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(order));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentProviderAdapter.initiatePayment(any())).thenReturn(new PaymentInitiationResult(
                "   ",
                "https://openapi.alipay.com/gateway.do?retry"
        ));

        StartBillingOrderPaymentResponse response = service.startPayment("josh", "po_123", paymentRequest("PC_WEB"));

        assertThat(response.redirectUrl()).contains("retry");
        assertThat(order.getProviderOrderRef()).isEqualTo("existing-ref");
        verify(paymentOrderRepository).save(order);
    }

    @Test
    void startPayment_WhenOrderBelongsToAnotherUser_ThrowsNotFound() {
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_002);
    }

    @Test
    void startPayment_WhenAnotherBlockingOrderExists_ThrowsConflict() {
        PaymentOrder targetOrder = payableOrder("po_123", PaymentOrderStatus.CREATED);
        PaymentOrder otherBlockingOrder = payableOrder("po_456", PaymentOrderStatus.CREATED);
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(targetOrder, otherBlockingOrder));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(targetOrder));

        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
        verify(paymentProviderAdapter, never()).initiatePayment(any());
    }

    @Test
    void startPayment_WhenConcurrentRequestsRace_OnlyOneCanEnterProviderStart() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.CREATED);
        PaymentOrder otherBlockingOrder = payableOrder("po_other", PaymentOrderStatus.CREATED);
        AtomicInteger lockProbeCount = new AtomicInteger();
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenAnswer(invocation ->
                lockProbeCount.incrementAndGet() == 1 ? List.of(order) : List.of(order, otherBlockingOrder)
        );
        when(paymentProviderAdapter.initiatePayment(any())).thenReturn(new PaymentInitiationResult(
                "po_123",
                "https://openapi.alipay.com/gateway.do?pay"
        ));

        StartBillingOrderPaymentResponse firstResponse = service.startPayment("josh", "po_123", paymentRequest("PC_WEB"));

        assertThat(firstResponse.status()).isEqualTo("PENDING_PROVIDER");
        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
        verify(paymentProviderAdapter, times(1)).initiatePayment(any());
    }

    @Test
    void startPayment_WhenRenewalOrderNotActivated_ActivatesOrderAndRefreshesExpiry() {
        PaymentOrder renewalOrder = renewalOrder("po_renewal", null, LocalDateTime.of(2026, 4, 10, 0, 30));
        when(paymentOrderRepository.findByOrderNoAndUserId("po_renewal", 101L)).thenReturn(Optional.of(renewalOrder));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(renewalOrder));
        when(paymentProviderAdapter.initiatePayment(any())).thenReturn(new PaymentInitiationResult(
                "po_renewal",
                "https://openapi.alipay.com/gateway.do?renewal"
        ));

        StartBillingOrderPaymentResponse response = service.startPayment("josh", "po_renewal", paymentRequest("PC_WEB"));

        assertThat(response.status()).isEqualTo("PENDING_PROVIDER");
        assertThat(renewalOrder.getPayableActivatedAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
        assertThat(renewalOrder.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 30));
    }

    @Test
    void startPayment_WhenOrderNotActivated_ThrowsConflict() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.CREATED);
        order.setPayableActivatedAt(null);
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(order));

        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
        verify(paymentProviderAdapter, never()).initiatePayment(any());
    }

    @Test
    void startPayment_WhenOrderStatusNotPayable_ThrowsConflict() {
        PaymentOrder order = payableOrder("po_123", PaymentOrderStatus.SUCCEEDED);
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.findActivePayableOrdersByUserIdForUpdate(101L)).thenReturn(List.of(order));

        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
        verify(paymentProviderAdapter, never()).initiatePayment(any());
    }

    @Test
    void startPayment_WhenPaymentDisabled_RejectsPaymentInitiation() {
        doThrow(new BusinessException(ErrorCode.PAYMENT_006, "Payment temporarily disabled"))
                .when(runtimeSwitchService)
                .requirePaymentEnabled();

        assertThatThrownBy(() -> service.startPayment("josh", "po_123", paymentRequest("PC_WEB")))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_006);
                    assertThat(businessException.getMessage()).isEqualTo("Payment temporarily disabled");
                });
        verify(paymentOrderRepository, never()).findByOrderNoAndUserId(any(), any());
        verify(paymentProviderAdapter, never()).initiatePayment(any());
    }

    private StartBillingOrderPaymentRequest paymentRequest(String terminal) {
        StartBillingOrderPaymentRequest request = new StartBillingOrderPaymentRequest();
        request.setTerminal(terminal);
        request.setReturnUrl("https://app.example.com/payments/result");
        return request;
    }

    private PaymentOrder payableOrder(String orderNo, PaymentOrderStatus status) {
        return PaymentOrder.builder()
                .id(orderNo.equals("po_123") ? 11L : 12L)
                .externalId(UUID.randomUUID())
                .orderNo(orderNo)
                .userId(101L)
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .bizRefType("CREDIT_PURCHASE_SKU")
                .bizRefId("credits-basic")
                .provider("alipay")
                .amount(new BigDecimal("9.900000"))
                .currency("USD")
                .status(status)
                .idempotencyKey("idem-" + orderNo)
                .providerOrderRef(orderNo)
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\"}")
                .entitlementSnapshot("[]")
                .payableActivatedAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .createdAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .expiresAt(LocalDateTime.of(2026, 4, 1, 0, 30))
                .build();
    }

    private PaymentOrder renewalOrder(String orderNo, LocalDateTime payableActivatedAt, LocalDateTime expiresAt) {
        return PaymentOrder.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .orderNo(orderNo)
                .userId(101L)
                .orderType(PaymentOrderType.SUBSCRIPTION_RENEWAL)
                .bizRefType("SUBSCRIPTION_CONTRACT")
                .bizRefId("contract-1")
                .subscriptionContractId(31L)
                .provider("alipay")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .idempotencyKey("idem-" + orderNo)
                .providerOrderRef(orderNo)
                .pricingSnapshot("{\"snapshotType\":\"PLAN\",\"amount\":29.900000,\"currency\":\"USD\",\"billingPlanId\":1,\"billingPlanVersionId\":1,\"billingCycle\":\"MONTHLY\"}")
                .entitlementSnapshot("[]")
                .payableActivatedAt(payableActivatedAt)
                .createdAt(LocalDateTime.of(2026, 3, 20, 0, 0))
                .expiresAt(expiresAt)
                .build();
    }

}
