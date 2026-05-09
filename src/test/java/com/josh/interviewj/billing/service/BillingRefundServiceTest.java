package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.request.AdminReviewBillingRefundRequest;
import com.josh.interviewj.billing.dto.request.CreateBillingRefundRequest;
import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingRefundRequest;
import com.josh.interviewj.billing.model.BillingRefundStatus;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.provider.PaymentProviderAdapter;
import com.josh.interviewj.billing.provider.PaymentProviderRegistry;
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingRefundRequestRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingRefundServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private BillingRefundRequestRepository billingRefundRequestRepository;

    @Mock
    private PaymentProviderRegistry paymentProviderRegistry;

    @Mock
    private PaymentProviderAdapter paymentProviderAdapter;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private CreditLotRepository creditLotRepository;

    @Mock
    private BillingRefundApplicationService billingRefundApplicationService;

    @Mock
    private BillingReconciliationService billingReconciliationService;

    private BillingRefundService service;

    @BeforeEach
    void setUp() {
        service = new BillingRefundService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                userRepository,
                paymentOrderRepository,
                billingRefundRequestRepository,
                paymentProviderRegistry,
                paymentEventRepository,
                billingEventRepository,
                creditLotRepository,
                billingRefundApplicationService,
                billingReconciliationService
        );
        lenient().when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));
        lenient().when(paymentProviderRegistry.requireAdapter("alipay")).thenReturn(paymentProviderAdapter);
        lenient().when(billingRefundRequestRepository.save(any(BillingRefundRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createRefundRequest_WhenOrderNotSucceeded_ThrowsConflict() {
        when(paymentOrderRepository.findByOrderNoAndUserId("po_123", 101L)).thenReturn(Optional.of(paymentOrder(PaymentOrderStatus.CREATED, PaymentOrderType.SUBSCRIPTION_PURCHASE)));

        CreateBillingRefundRequest request = new CreateBillingRefundRequest();
        request.setOrderNo("po_123");
        request.setRequestedAmount(new BigDecimal("29.900000"));
        request.setReason("want refund");

        assertThatThrownBy(() -> service.createRefundRequest("josh", request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
    }

    @Test
    void listPendingReviewRequests_ReturnsPendingOnly() {
        when(billingRefundRequestRepository.findByStatusOrderByCreatedAtDescIdDesc(BillingRefundStatus.PENDING_REVIEW))
                .thenReturn(List.of(BillingRefundRequest.builder()
                        .id(11L)
                        .paymentOrderId(21L)
                        .userId(101L)
                        .requestedAmount(new BigDecimal("29.900000"))
                        .currency("USD")
                        .reason("refund")
                        .status(BillingRefundStatus.PENDING_REVIEW)
                        .build()));

        assertThat(service.listPendingReviewRequests()).hasSize(1);
    }

    @Test
    void reviewRefundRequest_WhenDecisionReject_MarksRejected() {
        BillingRefundRequest refundRequest = pendingRefundRequest();
        when(billingRefundRequestRepository.findById(11L)).thenReturn(Optional.of(refundRequest));

        AdminReviewBillingRefundRequest request = new AdminReviewBillingRefundRequest();
        request.setDecision("REJECT");
        request.setComment("not eligible");

        service.reviewRefundRequest(999L, 11L, request);

        assertThat(refundRequest.getStatus()).isEqualTo(BillingRefundStatus.REJECTED);
        assertThat(refundRequest.getReviewComment()).isEqualTo("not eligible");
    }

    @Test
    void reviewRefundRequest_WhenCreditPurchasePartiallyConsumed_ThrowsConflict() {
        BillingRefundRequest refundRequest = pendingRefundRequest();
        PaymentOrder order = paymentOrder(PaymentOrderStatus.SUCCEEDED, PaymentOrderType.CREDIT_PURCHASE);
        when(billingRefundRequestRepository.findById(11L)).thenReturn(Optional.of(refundRequest));
        when(paymentOrderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentEventRepository.findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(21L, PaymentEventProcessStatus.APPLIED))
                .thenReturn(Optional.of(PaymentEvent.builder().id(31L).providerEventId("evt_paid").build()));
        when(billingEventRepository.findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
                101L, BillingEventType.CREDIT_PURCHASE_GRANTED, "PAYMENT_EVENT", "evt_paid"
        )).thenReturn(Optional.of(BillingEvent.builder().id(41L).build()));
        when(creditLotRepository.findBySourceBillingEventId(41L)).thenReturn(Optional.of(CreditLot.builder()
                .id(51L)
                .sourceBillingEventId(41L)
                .originalAmountMicros(100_000L)
                .remainingAmountMicros(40_000L)
                .status(CreditLotStatus.ACTIVE)
                .build()));

        AdminReviewBillingRefundRequest request = new AdminReviewBillingRefundRequest();
        request.setDecision("APPROVE");
        request.setComment("approve");

        assertThatThrownBy(() -> service.reviewRefundRequest(999L, 11L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.PAYMENT_004);
        verify(paymentProviderAdapter, never()).refund(any());
    }

    @Test
    void reviewRefundRequest_WhenApproved_CallsAdapterRefundAndDelegatesApplication() {
        BillingRefundRequest refundRequest = pendingRefundRequest();
        PaymentOrder order = paymentOrder(PaymentOrderStatus.SUCCEEDED, PaymentOrderType.SUBSCRIPTION_PURCHASE);
        when(billingRefundRequestRepository.findById(11L)).thenReturn(Optional.of(refundRequest));
        when(paymentOrderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentProviderAdapter.refund(any())).thenReturn(new PaymentRefundResult(
                "refund_123",
                "REFUND_SUCCESS",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 10),
                java.util.Map.of()
        ));

        AdminReviewBillingRefundRequest request = new AdminReviewBillingRefundRequest();
        request.setDecision("APPROVE");
        request.setComment("approve");

        service.reviewRefundRequest(999L, 11L, request);

        verify(paymentProviderAdapter).refund(any());
        verify(billingRefundApplicationService).applyApprovedRefund(eq(refundRequest), eq(order), any());
    }

    @Test
    void reviewRefundRequest_WhenProviderReturnsProcessing_CreatesReconciliationCase() {
        BillingRefundRequest refundRequest = pendingRefundRequest();
        PaymentOrder order = paymentOrder(PaymentOrderStatus.SUCCEEDED, PaymentOrderType.SUBSCRIPTION_PURCHASE);
        when(billingRefundRequestRepository.findById(11L)).thenReturn(Optional.of(refundRequest));
        when(paymentOrderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentProviderAdapter.refund(any())).thenReturn(new PaymentRefundResult(
                "refund_123",
                "REFUND_PROCESSING",
                new BigDecimal("29.900000"),
                "USD",
                null,
                java.util.Map.of("providerStatus", "REFUND_PROCESSING")
        ));

        AdminReviewBillingRefundRequest request = new AdminReviewBillingRefundRequest();
        request.setDecision("APPROVE");
        request.setComment("approve");

        service.reviewRefundRequest(999L, 11L, request);

        assertThat(refundRequest.getStatus()).isEqualTo(BillingRefundStatus.REQUIRES_RECONCILIATION);
        verify(billingReconciliationService).createCase(eq(101L), eq(21L), eq(null), eq(null), eq("BILLING_REFUND_REQUEST"), eq("REFUND_REQUIRES_REVIEW"), any());
        verify(billingRefundApplicationService, never()).applyApprovedRefund(any(), any(), any());
    }

    private BillingRefundRequest pendingRefundRequest() {
        return BillingRefundRequest.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .paymentOrderId(21L)
                .userId(101L)
                .requestedAmount(new BigDecimal("29.900000"))
                .currency("USD")
                .reason("refund")
                .status(BillingRefundStatus.PENDING_REVIEW)
                .build();
    }

    private PaymentOrder paymentOrder(PaymentOrderStatus status, PaymentOrderType orderType) {
        return PaymentOrder.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .orderNo("po_123")
                .userId(101L)
                .orderType(orderType)
                .bizRefType(orderType == PaymentOrderType.CREDIT_PURCHASE ? "CREDIT_PURCHASE_SKU" : "BILLING_PLAN")
                .bizRefId(orderType == PaymentOrderType.CREDIT_PURCHASE ? "credits-basic" : "plus")
                .provider("alipay")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(status)
                .providerOrderRef("po_123")
                .idempotencyKey("idem")
                .pricingSnapshot("{}")
                .entitlementSnapshot("[]")
                .build();
    }
}
