package com.josh.interviewj.billing.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.dto.response.UserBillingEntitlementsResponse;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.usage.service.CreditFormattingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingEntitlementQueryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BillingPlanRepository billingPlanRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private CreditWalletRepository creditWalletRepository;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    private BillingEntitlementQueryService service;

    @BeforeEach
    void setUp() {
        service = new BillingEntitlementQueryService(
                Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC),
                new BillingSnapshotCodec(JsonMapper.builder().build()),
                new CreditFormattingService(),
                userRepository,
                billingPlanRepository,
                billingPlanVersionRepository,
                billingEventRepository,
                creditWalletRepository,
                subscriptionContractRepository,
                subscriptionQuotaGrantRepository
        );
    }

    @Test
    void getCurrentUserEntitlements_ClampsNegativePurchasedBalanceButKeepsSubscriptionRemaining() {
        User user = User.builder().id(101L).externalId(UUID.randomUUID()).username("josh").email("josh@example.com").password("hashed").build();
        SubscriptionContract contract = SubscriptionContract.builder()
                .id(21L)
                .userId(101L)
                .billingPlanId(31L)
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                .currentPeriodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build();
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.of(contract));
        when(subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(21L)).thenReturn(List.of(
                SubscriptionQuotaGrant.builder()
                        .subscriptionContractId(21L)
                        .periodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .periodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                        .bucketCode("RESUME_CREDITS")
                        .grantedAmountMicros(500_000L)
                        .usedAmountMicros(100_000L)
                        .expiredAmountMicros(0L)
                        .build()
        ));
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .userId(101L)
                .purchasedBalanceMicros(-50_000L)
                .build()));
        when(billingPlanRepository.findById(31L)).thenReturn(Optional.of(BillingPlan.builder()
                .id(31L)
                .planCode("plus")
                .tierCode("plus")
                .displayName("Plus")
                .build()));

        UserBillingEntitlementsResponse response = service.getCurrentUserEntitlements("josh");

        assertThat(response.getPurchasedBalanceMicros()).isEqualTo(-50_000L);
        assertThat(response.getAvailablePurchasedBalanceMicros()).isZero();
        assertThat(response.getSubscriptionBuckets()).hasSize(1);
        assertThat(response.getDisplayTotalCreditsMicros()).isEqualTo(400_000L);
    }

    @Test
    void getCurrentUserSubscription_ReturnsActualBillingCycleFromPlanVersion() {
        User user = User.builder().id(101L).externalId(UUID.randomUUID()).username("josh").email("josh@example.com").password("hashed").build();
        SubscriptionContract contract = SubscriptionContract.builder()
                .id(22L)
                .userId(101L)
                .billingPlanId(31L)
                .billingPlanVersionId(41L)
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                .currentPeriodEnd(LocalDateTime.of(2027, 4, 1, 0, 0))
                .build();

        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(user));
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.of(contract));
        when(billingPlanRepository.findById(31L)).thenReturn(Optional.of(BillingPlan.builder()
                .id(31L)
                .planCode("plus")
                .tierCode("plus")
                .displayName("Plus")
                .build()));
        when(billingPlanVersionRepository.findById(41L)).thenReturn(Optional.of(BillingPlanVersion.builder()
                .id(41L)
                .billingPlanId(31L)
                .versionNo(2)
                .billingCycle("YEARLY")
                .build()));

        assertThat(service.getCurrentUserSubscription("josh").getCurrentBillingCycle()).isEqualTo("YEARLY");
    }
}
