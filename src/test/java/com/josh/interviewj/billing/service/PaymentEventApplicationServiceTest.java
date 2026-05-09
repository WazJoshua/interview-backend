package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.PaymentEvent;
import com.josh.interviewj.billing.model.PaymentEventProcessStatus;
import com.josh.interviewj.billing.model.PaymentOrderStatus;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.provider.VerifiedPaymentNotification;
import com.josh.interviewj.billing.repository.PaymentEventRepository;
import com.josh.interviewj.billing.repository.PaymentOrderRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventApplicationServiceTest {

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private BillingReconciliationService billingReconciliationService;

    @Mock
    private BillingRefundApplicationService billingRefundApplicationService;

    @Mock
    private CreditBalanceProjectionService creditBalanceProjectionService;

    @Mock
    private SubscriptionLifecycleService subscriptionLifecycleService;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    private PaymentEventApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentEventApplicationService(
                Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC),
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                billingEventService,
                billingReconciliationService,
                billingRefundApplicationService,
                creditBalanceProjectionService,
                subscriptionLifecycleService,
                paymentEventRepository,
                paymentOrderRepository
        );
    }

    @Test
    void applyVerifiedEvent_WhenOrderExpired_CreatesReconciliationCaseAndSkipsGrant() {
        PaymentEvent event = PaymentEvent.builder()
                .id(11L)
                .provider("mockpay")
                .providerEventId("evt-1")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(21L)
                .userId(101L)
                .orderNo("po-1")
                .provider("mockpay")
                .providerOrderRef("po-1")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .amount(new BigDecimal("10.000000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\",\"amount\":10.000000,\"currency\":\"USD\",\"skuCode\":\"credits\",\"creditsAmountMicros\":100000}")
                .build();

        when(paymentEventRepository.findById(11L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(11L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findByProviderAndProviderOrderRef("mockpay", "po-1")).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyVerifiedEvent(11L, notification());

        verify(billingReconciliationService).createCase(eq(101L), eq(21L), eq(11L), eq(null), eq("PAYMENT_ORDER"), eq("LATE_SUCCESS_AFTER_EXPIRY"), any());
        verify(creditBalanceProjectionService, never()).grantPurchasedCredits(anyLong(), any(), anyLong(), any(), any());
        verify(subscriptionLifecycleService, never()).applySubscriptionPurchase(any(), any());
    }

    @Test
    void applyVerifiedEvent_WhenEventTypeIsNotSuccessful_DoesNotApplyOrder() {
        PaymentEvent event = PaymentEvent.builder()
                .id(12L)
                .paymentOrderId(22L)
                .provider("mockpay")
                .providerEventId("evt-pending")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(22L)
                .userId(101L)
                .orderNo("po-2")
                .provider("mockpay")
                .providerOrderRef("po-2")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .amount(new BigDecimal("10.000000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\",\"amount\":10.000000,\"currency\":\"USD\",\"skuCode\":\"credits\",\"creditsAmountMicros\":100000}")
                .build();

        when(paymentEventRepository.findById(12L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(12L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findById(22L)).thenReturn(Optional.of(order));

        service.applyVerifiedEvent(12L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-pending",
                "po-2",
                null,
                "PAYMENT_PENDING",
                new BigDecimal("10.000000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        verify(creditBalanceProjectionService, never()).grantPurchasedCredits(anyLong(), any(), anyLong(), any(), any());
        verify(subscriptionLifecycleService, never()).applySubscriptionPurchase(any(), any());
        verify(paymentEventRepository, times(2)).save(any(PaymentEvent.class));
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.AWAITING_CONFIRMATION);
        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.FAILED_RETRYABLE);
    }

    @Test
    void applyVerifiedEvent_WhenPendingThenSucceededWithSameEventId_CanEventuallyApply() {
        PaymentEvent event = PaymentEvent.builder()
                .id(13L)
                .paymentOrderId(23L)
                .provider("mockpay")
                .providerEventId("evt-retryable")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(23L)
                .userId(101L)
                .orderNo("po-3")
                .provider("mockpay")
                .providerOrderRef("po-3")
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PLAN\",\"amount\":29.900000,\"currency\":\"USD\",\"billingPlanId\":1,\"billingPlanVersionId\":1,\"billingCycle\":\"MONTHLY\"}")
                .build();

        SubscriptionContract contract = SubscriptionContract.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .userId(101L)
                .billingPlanId(1L)
                .billingPlanVersionId(1L)
                .status(com.josh.interviewj.billing.model.SubscriptionContractStatus.ACTIVE)
                .build();

        when(paymentEventRepository.findById(13L)).thenReturn(Optional.of(event), Optional.of(event), Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(13L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1, 1);
        when(paymentOrderRepository.findById(23L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subscriptionLifecycleService.applySubscriptionPurchase(any(), any())).thenReturn(contract);

        service.applyVerifiedEvent(13L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-retryable",
                "po-3",
                null,
                "PAYMENT_PENDING",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.FAILED_RETRYABLE);
        verify(subscriptionLifecycleService, never()).applySubscriptionPurchase(any(), any());

        service.applyVerifiedEvent(13L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-retryable",
                "po-3",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.APPLIED);
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.SUCCEEDED);
        verify(subscriptionLifecycleService, times(1)).applySubscriptionPurchase(any(), any());
    }

    @Test
    void applyVerifiedEvent_WhenReversalEventDetected_MarksOrderForReviewWithoutTerminalFailure() {
        PaymentEvent event = PaymentEvent.builder()
                .id(16L)
                .paymentOrderId(26L)
                .provider("mockpay")
                .providerEventId("evt-reversal")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(26L)
                .userId(101L)
                .orderNo("po-6")
                .provider("mockpay")
                .providerOrderRef("po-6")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .amount(new BigDecimal("10.000000"))
                .currency("USD")
                .status(PaymentOrderStatus.SUCCEEDED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\",\"amount\":10.000000,\"currency\":\"USD\",\"skuCode\":\"credits\",\"creditsAmountMicros\":100000}")
                .build();

        when(paymentEventRepository.findById(16L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(16L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findById(26L)).thenReturn(Optional.of(order));
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyVerifiedEvent(16L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-reversal",
                "po-6",
                null,
                "CHARGEBACK",
                new BigDecimal("10.000000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.FAILED_RETRYABLE);
        verify(billingRefundApplicationService).handleProviderReversal(eq(order), eq(event), any());
    }

    @Test
    void applyVerifiedEvent_WhenAmountMismatch_CreatesReconciliationCaseAndKeepsPayment005() {
        PaymentEvent event = PaymentEvent.builder()
                .id(17L)
                .paymentOrderId(27L)
                .provider("mockpay")
                .providerEventId("evt-mismatch")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(27L)
                .userId(101L)
                .orderNo("po-7")
                .provider("mockpay")
                .providerOrderRef("po-7")
                .orderType(PaymentOrderType.CREDIT_PURCHASE)
                .amount(new BigDecimal("10.000000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PURCHASE\",\"amount\":10.000000,\"currency\":\"USD\",\"skuCode\":\"credits\",\"creditsAmountMicros\":100000}")
                .build();

        when(paymentEventRepository.findById(17L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(17L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findById(27L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.applyVerifiedEvent(17L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-mismatch",
                "po-7",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("11.000000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REQUIRES_RECONCILIATION);
        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.FAILED_TERMINAL);
        assertThat(event.getLastErrorCode()).isEqualTo("PAYMENT_005");
        verify(billingReconciliationService).createCase(
                eq(101L), eq(27L), eq(17L), eq(null),
                eq("PAYMENT_ORDER"),
                eq("AMOUNT_MISMATCH"),
                any()
        );
    }

    @Test
    void applyVerifiedEvent_WhenSubscriptionPurchaseRequiresReconciliation_MarksOrderForReconciliation() {
        // This tests the scenario where post-cutover order lacks inventory reservation
        // and SubscriptionLifecycleService returns null to signal reconciliation needed
        PaymentEvent event = PaymentEvent.builder()
                .id(14L)
                .paymentOrderId(24L)
                .provider("mockpay")
                .providerEventId("evt-reconciliation")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(24L)
                .userId(101L)
                .orderNo("po-4")
                .provider("mockpay")
                .providerOrderRef("po-4")
                .orderType(PaymentOrderType.SUBSCRIPTION_PURCHASE)
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PLAN\",\"amount\":29.900000,\"currency\":\"USD\",\"billingPlanId\":1,\"billingPlanVersionId\":1,\"billingCycle\":\"MONTHLY\"}")
                .build();

        when(paymentEventRepository.findById(14L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(14L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findById(24L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Return null signals that order requires reconciliation (post-cutover missing reservation)
        when(subscriptionLifecycleService.applySubscriptionPurchase(any(), any())).thenReturn(null);

        service.applyVerifiedEvent(14L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-reconciliation",
                "po-4",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        // Verify order is marked for reconciliation instead of SUCCEEDED
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REQUIRES_RECONCILIATION);
        // Verify reconciliation case is created
        verify(billingReconciliationService).createCase(
                eq(101L), eq(24L), eq(14L), eq(null),
                eq("INVENTORY_RECONCILIATION"),
                eq("POST_CUTOVER_ORDER_WITHOUT_RESERVATION"),
                any()
        );
        // Verify event is marked as APPLIED (not FAILED_TERMINAL)
        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.APPLIED);
        // Verify subscription fulfillment was called but returned null
        verify(subscriptionLifecycleService, times(1)).applySubscriptionPurchase(any(), any());
    }

    @Test
    void applyVerifiedEvent_WhenSubscriptionRenewalRequiresReconciliation_MarksOrderForReconciliation() {
        // This tests the scenario where post-cutover renewal order lacks inventory reservation
        PaymentEvent event = PaymentEvent.builder()
                .id(15L)
                .paymentOrderId(25L)
                .provider("mockpay")
                .providerEventId("evt-reconciliation-renewal")
                .processStatus(PaymentEventProcessStatus.RECEIVED)
                .occurredAt(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        PaymentOrder order = PaymentOrder.builder()
                .id(25L)
                .userId(101L)
                .orderNo("po-5")
                .provider("mockpay")
                .providerOrderRef("po-5")
                .orderType(PaymentOrderType.SUBSCRIPTION_RENEWAL)
                .subscriptionContractId(31L)
                .renewalPeriodStart(LocalDateTime.of(2026, 4, 2, 0, 0))
                .renewalPeriodEnd(LocalDateTime.of(2026, 5, 2, 0, 0))
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .status(PaymentOrderStatus.CREATED)
                .expiresAt(LocalDateTime.of(2026, 4, 3, 0, 0))
                .pricingSnapshot("{\"snapshotType\":\"PLAN\",\"amount\":29.900000,\"currency\":\"USD\",\"billingPlanId\":1,\"billingPlanVersionId\":1,\"billingCycle\":\"MONTHLY\"}")
                .build();

        when(paymentEventRepository.findById(15L)).thenReturn(Optional.of(event), Optional.of(event));
        when(paymentEventRepository.save(any(PaymentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentEventRepository.claimForApplying(eq(15L), any(), eq(PaymentEventProcessStatus.APPLYING), any())).thenReturn(1);
        when(paymentOrderRepository.findById(25L)).thenReturn(Optional.of(order));
        when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Return null signals that order requires reconciliation (post-cutover missing reservation)
        when(subscriptionLifecycleService.applySubscriptionRenewal(any(), any())).thenReturn(null);

        service.applyVerifiedEvent(15L, new VerifiedPaymentNotification(
                "mockpay",
                "evt-reconciliation-renewal",
                "po-5",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("29.900000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        ));

        // Verify order is marked for reconciliation instead of SUCCEEDED
        assertThat(order.getStatus()).isEqualTo(PaymentOrderStatus.REQUIRES_RECONCILIATION);
        // Verify reconciliation case is created
        verify(billingReconciliationService).createCase(
                eq(101L), eq(25L), eq(15L), eq(31L),
                eq("INVENTORY_RECONCILIATION"),
                eq("POST_CUTOVER_ORDER_WITHOUT_RESERVATION"),
                any()
        );
        // Verify event is marked as APPLIED (not FAILED_TERMINAL)
        assertThat(event.getProcessStatus()).isEqualTo(PaymentEventProcessStatus.APPLIED);
        // Verify subscription fulfillment was called but returned null
        verify(subscriptionLifecycleService, times(1)).applySubscriptionRenewal(any(), any());
    }

    private VerifiedPaymentNotification notification() {
        return new VerifiedPaymentNotification(
                "mockpay",
                "evt-1",
                "po-1",
                null,
                "PAYMENT_SUCCEEDED",
                new BigDecimal("10.000000"),
                "USD",
                LocalDateTime.of(2026, 4, 2, 0, 0),
                "{\"ok\":true}",
                Map.of()
        );
    }
}
