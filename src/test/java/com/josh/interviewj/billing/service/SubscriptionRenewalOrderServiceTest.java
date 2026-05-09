package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.config.BillingProperties;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanEntitlementItem;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.PaymentOrder;
import com.josh.interviewj.billing.model.PaymentOrderType;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionRenewalOrderServiceTest {

    @Mock
    private InventoryReservationService inventoryReservationService;

    @Mock
    private BillingPlanRepository billingPlanRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private BillingPlanEntitlementItemRepository entitlementItemRepository;

    @Mock
    private PaymentOrderRepository paymentOrderRepository;

    private SubscriptionRenewalOrderService service;

    @BeforeEach
    void setUp() {
        BillingProperties billingProperties = new BillingProperties();
        billingProperties.getOrder().setDefaultExpireMinutes(30);
        service = new SubscriptionRenewalOrderService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                billingProperties,
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                inventoryReservationService,
                billingPlanRepository,
                billingPlanVersionRepository,
                entitlementItemRepository,
                paymentOrderRepository
        );
        lenient().when(paymentOrderRepository.save(any(PaymentOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void ensureNextRenewalOrder_WhenCreated_NewOrderIsNotActivatedForPayment() {
        SubscriptionContract contract = SubscriptionContract.builder()
                .id(31L)
                .externalId(UUID.randomUUID())
                .userId(101L)
                .billingPlanId(11L)
                .billingPlanVersionId(21L)
                .provider("alipay")
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodEnd(LocalDateTime.of(2026, 4, 2, 0, 0))
                .build();
        when(billingPlanVersionRepository.findById(21L)).thenReturn(Optional.of(planVersion()));
        when(billingPlanRepository.findById(11L)).thenReturn(Optional.of(plan()));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(21L)).thenReturn(List.of(entitlement()));
        when(paymentOrderRepository.findBySubscriptionContractIdAndRenewalPeriodStartAndRenewalPeriodEndAndOrderType(
                31L,
                LocalDateTime.of(2026, 4, 2, 0, 0),
                LocalDateTime.of(2026, 5, 2, 0, 0),
                PaymentOrderType.SUBSCRIPTION_RENEWAL
        )).thenReturn(Optional.empty());

        PaymentOrder order = service.ensureNextRenewalOrder(contract);

        assertThat(order.getOrderType()).isEqualTo(PaymentOrderType.SUBSCRIPTION_RENEWAL);
        assertThat(order.getPayableActivatedAt()).isNull();
    }

    private BillingPlan plan() {
        return BillingPlan.builder()
                .id(11L)
                .externalId(UUID.randomUUID())
                .planCode("plus")
                .tierCode("plus")
                .displayName("Plus")
                .active(true)
                .build();
    }

    private BillingPlanVersion planVersion() {
        return BillingPlanVersion.builder()
                .id(21L)
                .externalId(UUID.randomUUID())
                .billingPlanId(11L)
                .versionNo(1)
                .billingCycle("MONTHLY")
                .amount(new BigDecimal("29.900000"))
                .currency("USD")
                .saleEnabled(true)
                .renewalEnabled(true)
                .effectiveFrom(LocalDateTime.of(2026, 4, 1, 0, 0))
                .build();
    }

    private BillingPlanEntitlementItem entitlement() {
        return BillingPlanEntitlementItem.builder()
                .billingPlanVersionId(21L)
                .bucketCode("RESUME_CREDITS")
                .grantAmountMicros(500_000L)
                .grantType("PERIODIC")
                .build();
    }
}
