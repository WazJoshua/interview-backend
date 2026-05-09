package com.josh.interviewj.billing.service;

import com.josh.interviewj.billing.model.BillingEvent;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.repository.BillingPlanEntitlementItemRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceTest {

    @Mock
    private BillingSnapshotCodec billingSnapshotCodec;

    @Mock
    private BillingEventService billingEventService;

    @Mock
    private InventoryReservationService inventoryReservationService;

    @Mock
    private LegacyCreditPolicyProjectionService legacyCreditPolicyProjectionService;

    @Mock
    private SubscriptionQuotaGrantService subscriptionQuotaGrantService;

    @Mock
    private SubscriptionRenewalOrderService subscriptionRenewalOrderService;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private BillingPlanEntitlementItemRepository entitlementItemRepository;

    private Clock clock;
    private SubscriptionLifecycleService sut;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-04-03T10:00:00Z"), ZoneOffset.UTC);
        sut = new SubscriptionLifecycleService(
                billingSnapshotCodec,
                billingEventService,
                inventoryReservationService,
                legacyCreditPolicyProjectionService,
                subscriptionQuotaGrantService,
                subscriptionRenewalOrderService,
                subscriptionContractRepository,
                billingPlanVersionRepository,
                entitlementItemRepository,
                clock
        );
    }

    @Test
    void applyActivationCodeSubscriptionCreatesContractWithAutoRenewFalse() {
        Long userId = 1L;
        Long planVersionId = 10L;
        BillingPlanVersion version = BillingPlanVersion.builder()
                .id(planVersionId)
                .billingPlanId(100L)
                .build();
        when(billingPlanVersionRepository.findById(planVersionId)).thenReturn(Optional.of(version));
        when(entitlementItemRepository.findByBillingPlanVersionIdOrderByBucketCodeAsc(planVersionId))
                .thenReturn(List.of());
        BillingEvent mockEvent = BillingEvent.builder().id(999L).build();
        when(billingEventService.createOrGet(any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(mockEvent);
        when(subscriptionContractRepository.save(any(SubscriptionContract.class)))
                .thenAnswer(invocation -> {
                    SubscriptionContract contract = invocation.getArgument(0);
                    contract.setId(50L);
                    return contract;
                });

        SubscriptionContract result = sut.applyActivationCodeSubscription(userId, planVersionId, 30, "act-123");

        assertThat(result.isAutoRenew()).isFalse();
        assertThat(result.getProvider()).isEqualTo("ACTIVATION_CODE");
        assertThat(result.getStatus()).isEqualTo(SubscriptionContractStatus.ACTIVE);
        assertThat(result.isCancelAtPeriodEnd()).isFalse();
        assertThat(result.getCurrentPeriodEnd()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(30));
        verify(legacyCreditPolicyProjectionService).projectForUser(userId);
    }
}
