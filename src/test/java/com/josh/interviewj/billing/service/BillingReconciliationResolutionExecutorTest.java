package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingEventType;
import com.josh.interviewj.billing.model.BillingReconciliationCase;
import com.josh.interviewj.billing.model.CreditLot;
import com.josh.interviewj.billing.model.CreditLotStatus;
import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.CreditLotRepository;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingReconciliationResolutionExecutorTest {

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private CreditBalanceProjectionService creditBalanceProjectionService;

    @Mock
    private CreditLotRepository creditLotRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private SubscriptionQuotaGrantService subscriptionQuotaGrantService;

    @Mock
    private LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;

    @Mock
    private SubscriptionRenewalOrderService subscriptionRenewalOrderService;

    private BillingReconciliationResolutionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BillingReconciliationResolutionExecutor(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                billingEventService,
                creditBalanceProjectionService,
                creditLotRepository,
                paymentOrderRepository,
                paymentEventRepository,
                billingEventRepository,
                subscriptionContractRepository,
                subscriptionQuotaGrantService,
                legacyCreditPolicyProjectionService,
                subscriptionRenewalOrderService
        );
    }

    @Test
    void execute_WhenFulfillManuallyForCreditOrder_GrantsCreditsAndMarksOrderSucceeded() {
        BillingReconciliationCase reconciliationCase = BillingReconciliationCase.builder()
                .id(11L)
                .userId(101L)
                .paymentOrderId(21L)
                .details("{}")
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(21L)
                .userId(101L)
                .orderNo("po_123")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .provider("alipay")
                .amount(new BigDecimal("9.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.REQUIRES_RECONCILIATION)
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\",\"amount\":9.900000,\"currency\":\"USD\",\"skuCode\":\"credits-basic\",\"creditsAmountMicros\":100000}")
                .build();
        when(paymentOrderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingEventService.createOrGet(
                eq(101L),
                eq(BillingEventType.CREDIT_PURCHASE_GRANTED),
                eq("BILLING_RECONCILIATION_CASE"),
                eq("11"),
                eq("reconciliation|11|credit-grant"),
                eq(100_000L),
                eq(null),
                any(),
                any()
        )).thenReturn(BillingEvent.builder().id(31L).build());

        executor.execute(reconciliationCase, "FULFILL_MANUALLY", Map.of("operator", "admin"));

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.SUCCEEDED);
        verify(creditBalanceProjectionService).grantPurchasedCredits(eq(101L), any(BillingEvent.class), eq(100_000L), eq(null), any());
    }

    @Test
    void execute_WhenRefundManuallyForCreditOrder_CreatesRefundFactAndReversesWallet() {
        BillingReconciliationCase reconciliationCase = BillingReconciliationCase.builder()
                .id(11L)
                .userId(101L)
                .paymentOrderId(21L)
                .details("{}")
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(21L)
                .userId(101L)
                .orderNo("po_123")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .status(PaymentOrderStatus.REQUIRES_RECONCILIATION)
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
        when(paymentOrderRepository.findById(21L)).thenReturn(Optional.of(order));
        when(paymentEventRepository.findTopByPaymentOrderIdAndProcessStatusOrderByOccurredAtDescIdDesc(21L, PaymentEventProcessStatus.APPLIED))
                .thenReturn(Optional.of(paymentEvent));
        when(billingEventRepository.findFirstByUserIdAndEventTypeAndSourceTypeAndSourceIdOrderByOccurredAtDescIdDesc(
                101L,
                BillingEventType.CREDIT_PURCHASE_GRANTED,
                "PAYMENT_EVENT",
                "evt_paid"
        )).thenReturn(Optional.of(grantEvent));
        when(creditLotRepository.findBySourceBillingEventId(41L)).thenReturn(Optional.of(lot));
        when(creditLotRepository.save(any(CreditLot.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(billingEventService.createOrGet(
                eq(101L),
                eq(BillingEventType.PAYMENT_REFUNDED),
                eq("BILLING_RECONCILIATION_CASE"),
                eq("11"),
                eq("reconciliation|11|manual-refund"),
                eq(-100_000L),
                eq(null),
                any(),
                any()
        )).thenReturn(BillingEvent.builder().id(61L).build());

        executor.execute(reconciliationCase, "REFUND_MANUALLY", Map.of());

        assertThat(lot.getStatus()).isEqualTo(CreditLotStatus.REVERSED);
        assertThat(lot.getRemainingAmountMicros()).isZero();
        verify(creditBalanceProjectionService).adjustWallet(101L, -100_000L);
    }

    @Test
    void execute_WhenCloseNoAction_DoesNotTriggerAnyBusinessAction() {
        executor.execute(BillingReconciliationCase.builder().id(11L).build(), "CLOSE_NO_ACTION", Map.of());

        verifyNoInteractions(
                billingEventService,
                creditBalanceProjectionService,
                creditLotRepository,
                paymentOrderRepository,
                paymentEventRepository,
                billingEventRepository,
                subscriptionContractRepository,
                subscriptionQuotaGrantService,
                legacyCreditPolicyProjectionService,
                subscriptionRenewalOrderService
        );
    }
}
