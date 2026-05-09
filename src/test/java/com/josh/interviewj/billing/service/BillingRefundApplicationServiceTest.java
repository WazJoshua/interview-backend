package com.josh.interviewj.billing.service;

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
import com.josh.interviewj.billing.provider.PaymentRefundResult;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingRefundRequestRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingRefundApplicationServiceTest {

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private CreditBalanceProjectionService creditBalanceProjectionService;

    @Mock
    private CreditLotRepository creditLotRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private BillingRefundRequestRepository billingRefundRequestRepository;

    @Mock
    private BillingReconciliationService billingReconciliationService;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    private BillingRefundApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BillingRefundApplicationService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                billingEventService,
                creditBalanceProjectionService,
                creditLotRepository,
                paymentEventRepository,
                billingEventRepository,
                billingRefundRequestRepository,
                billingReconciliationService,
                paymentOrderRepository
        );
        lenient().when(billingRefundRequestRepository.save(any(BillingRefundRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(creditLotRepository.save(any(CreditLot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void applyApprovedRefund_WhenCreditPurchaseRefunded_AdjustsWalletAndMarksLotReversed() {
        BillingRefundRequest refundRequest = BillingRefundRequest.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .paymentOrderId(21L)
                .userId(101L)
                .requestedAmount(new BigDecimal("29.900000"))
                .currency("USD")
                .reason("refund")
                .status(BillingRefundStatus.PENDING_REVIEW)
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(21L)
                .userId(101L)
                .orderNo("po_123")
                .provider("alipay")
                .providerOrderRef("po_123")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .status(PaymentOrderStatus.SUCCEEDED)
                .build();
        PaymentEvent paymentEvent = PaymentEvent.builder()
                .id(31L)
                .providerEventId("evt_paid")
                .processStatus(PaymentEventProcessStatus.APPLIED)
                .build();
        BillingEvent grantEvent = BillingEvent.builder().id(41L).build();
        CreditLot lot = CreditLot.builder()
                .id(51L)
                .sourceBillingEventId(41L)
                .originalAmountMicros(100_000L)
                .remainingAmountMicros(100_000L)
                .status(CreditLotStatus.ACTIVE)
                .build();
        when(paymentEventRepository.findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(21L, PaymentEventProcessStatus.APPLIED))
                .thenReturn(Optional.of(paymentEvent));
        when(billingEventRepository.findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
                101L, BillingEventType.CREDIT_PURCHASE_GRANTED, "PAYMENT_EVENT", "evt_paid"
        )).thenReturn(Optional.of(grantEvent));
        when(creditLotRepository.findBySourceBillingEventId(41L)).thenReturn(Optional.of(lot));
        when(billingEventService.createOrGet(eq(101L), eq(BillingEventType.PAYMENT_REFUNDED), eq("BILLING_REFUND_REQUEST"), eq("11"), eq("refund|11"), eq(-100_000L), eq(null), any(), any()))
                .thenReturn(BillingEvent.builder().id(61L).build());

        service.applyApprovedRefund(refundRequest, order, new PaymentRefundResult(
                "refund_123",
                "REFUND_SUCCESS",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 10),
                Map.of()
        ));

        assertThat(refundRequest.getStatus()).isEqualTo(BillingRefundStatus.REFUNDED);
        assertThat(lot.getStatus()).isEqualTo(CreditLotStatus.REVERSED);
        assertThat(lot.getRemainingAmountMicros()).isZero();
        verify(creditBalanceProjectionService).adjustWallet(101L, -100_000L);
    }

    @Test
    void handleProviderReversal_WhenRefundEventArrives_CreatesBillingEventAndReconciliationCase() {
        PaymentOrder order = PaymentOrder.builder()
                .id(21L)
                .userId(101L)
                .orderNo("po_123")
                .provider("alipay")
                .providerOrderRef("po_123")
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .subscriptionContractId(31L)
                .status(PaymentOrderStatus.SUCCEEDED)
                .build();
        PaymentEvent paymentEvent = PaymentEvent.builder()
                .id(71L)
                .provider("alipay")
                .providerEventId("evt_refund")
                .build();
        when(billingEventService.createOrGet(eq(101L), eq(BillingEventType.PAYMENT_REFUNDED), eq("PAYMENT_EVENT"), eq("evt_refund"), eq("reversal|evt_refund"), eq(0L), eq(null), any(), any()))
                .thenReturn(BillingEvent.builder().id(81L).build());

        service.handleProviderReversal(order, paymentEvent, new VerifiedPaymentNotification(
                "alipay",
                "evt_refund",
                "po_123",
                null,
                "REFUND",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 20),
                "{\"refund\":true}",
                Map.of()
        ));

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REQUIRES_RECONCILIATION);
        verify(paymentOrderRepository).save(order);
        verify(billingReconciliationService).createCase(eq(101L), eq(21L), eq(71L), eq(31L), eq("PAYMENT_EVENT"), eq("REVERSAL_EVENT_REQUIRES_REVIEW"), any());
    }
}
