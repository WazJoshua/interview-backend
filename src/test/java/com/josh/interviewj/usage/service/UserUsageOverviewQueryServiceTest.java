package com.josh.interviewj.usage.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.CreditWallet;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionContractStatus;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.billing.repository.BillingEventRepository;
import com.josh.interviewj.billing.repository.CreditWalletRepository;
import com.josh.interviewj.billing.repository.SubscriptionContractRepository;
import com.josh.interviewj.billing.repository.SubscriptionQuotaGrantRepository;
import com.josh.interviewj.usage.dto.response.UserUsageOverviewResponse;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import com.josh.interviewj.usage.repository.LlmUsageCreditLedgerRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserUsageOverviewQueryServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BillingPlanRepository billingPlanRepository;

    @Mock
    private BillingPlanVersionRepository billingPlanVersionRepository;

    @Mock
    private CreditWalletRepository creditWalletRepository;

    @Mock
    private SubscriptionContractRepository subscriptionContractRepository;

    @Mock
    private SubscriptionQuotaGrantRepository subscriptionQuotaGrantRepository;

    @Mock
    private BillingEventRepository billingEventRepository;

    @Mock
    private LlmUsageCreditLedgerRepository llmUsageCreditLedgerRepository;

    private UserUsageOverviewQueryService service;

    @BeforeEach
    void setUp() {
        CreditsBalanceSnapshotService snapshotService = new CreditsBalanceSnapshotService(
                Clock.fixed(Instant.parse("2026-04-09T09:00:00Z"), ZoneOffset.UTC),
                creditWalletRepository,
                subscriptionContractRepository,
                subscriptionQuotaGrantRepository,
                billingEventRepository,
                llmUsageCreditLedgerRepository
        );
        service = new UserUsageOverviewQueryService(
                Clock.fixed(Instant.parse("2026-04-09T09:00:00Z"), ZoneOffset.UTC),
                userRepository,
                billingPlanRepository,
                billingPlanVersionRepository,
                snapshotService,
                new CreditFormattingService()
        );
    }

    @Test
    void getCurrentUserOverview_NewUserWithoutCredits_ReturnsZeroState() {
        stubUser();
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());

        UserUsageOverviewResponse response = service.getCurrentUserOverview("josh");

        assertThat(response.getBalance().getTotalCreditsMicros()).isZero();
        assertThat(response.getBalance().isHasAnyCredits()).isFalse();
        assertThat(response.getSubscription().isActive()).isFalse();
        assertThat(response.getDefaultWindow().getWindowType()).isEqualTo(UsageHistoryWindowType.LAST_30_DAYS);
    }

    @Test
    void getCurrentUserOverview_PurchasedOnlyUser_HasNoSubscription() {
        stubUser();
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .userId(101L)
                .purchasedBalanceMicros(100_000L)
                .build()));
        when(billingEventRepository.sumNetPurchasedCreditsMicros(101L)).thenReturn(180_000L);
        when(llmUsageCreditLedgerRepository.sumPurchasedAllocatedCreditsMicros(101L)).thenReturn(80_000L);
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());

        UserUsageOverviewResponse response = service.getCurrentUserOverview("josh");

        assertThat(response.getBalance().getPurchasedTotalCreditsMicros()).isEqualTo(180_000L);
        assertThat(response.getBalance().getPurchasedUsedCreditsMicros()).isEqualTo(80_000L);
        assertThat(response.getBalance().getPurchasedAvailableCreditsMicros()).isEqualTo(100_000L);
        assertThat(response.getBalance().getSubscriptionAvailableCreditsMicros()).isZero();
        assertThat(response.getSubscription().isActive()).isFalse();
    }

    @Test
    void getCurrentUserOverview_SubscriptionOnlyUser_ReturnsBucketsAndPeriod() {
        stubUser();
        SubscriptionContract contract = activeContract();
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.empty());
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.of(contract));
        when(subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(21L)).thenReturn(List.of(
                SubscriptionQuotaGrant.builder()
                        .id(1L)
                        .subscriptionContractId(21L)
                        .periodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .periodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                        .bucketCode("KB_QUERY_CREDITS")
                        .grantedAmountMicros(500_000L)
                        .usedAmountMicros(120_000L)
                        .expiredAmountMicros(0L)
                        .build()
        ));
        when(billingPlanRepository.findById(31L)).thenReturn(Optional.of(BillingPlan.builder()
                .id(31L)
                .planCode("pro_monthly")
                .tierCode("PRO")
                .displayName("Pro")
                .build()));
        when(billingPlanVersionRepository.findById(41L)).thenReturn(Optional.of(BillingPlanVersion.builder()
                .id(41L)
                .billingPlanId(31L)
                .billingCycle("MONTHLY")
                .build()));

        UserUsageOverviewResponse response = service.getCurrentUserOverview("josh");

        assertThat(response.getSubscription().isActive()).isTrue();
        assertThat(response.getSubscription().getBucketCount()).isEqualTo(1);
        assertThat(response.getSubscription().getCurrentPeriodStart()).isNotNull();
        assertThat(response.getDefaultWindow().getWindowType()).isEqualTo(UsageHistoryWindowType.CURRENT_SUBSCRIPTION_PERIOD);
        assertThat(response.getBalance().getSubscriptionAvailableCreditsMicros()).isEqualTo(380_000L);
    }

    @Test
    void getCurrentUserOverview_MixedUser_ReturnsCombinedBalances() {
        stubUser();
        SubscriptionContract contract = activeContract();
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .userId(101L)
                .purchasedBalanceMicros(100_000L)
                .build()));
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.of(contract));
        when(subscriptionQuotaGrantRepository.findBySubscriptionContractIdOrderByPeriodEndAscIdAsc(21L)).thenReturn(List.of(
                SubscriptionQuotaGrant.builder()
                        .id(1L)
                        .subscriptionContractId(21L)
                        .periodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                        .periodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                        .bucketCode("RESUME_CREDITS")
                        .grantedAmountMicros(400_000L)
                        .usedAmountMicros(0L)
                        .expiredAmountMicros(0L)
                        .build()
        ));
        when(billingPlanRepository.findById(31L)).thenReturn(Optional.of(BillingPlan.builder()
                .id(31L)
                .planCode("pro_monthly")
                .tierCode("PRO")
                .displayName("Pro")
                .build()));
        when(billingPlanVersionRepository.findById(41L)).thenReturn(Optional.of(BillingPlanVersion.builder()
                .id(41L)
                .billingPlanId(31L)
                .billingCycle("MONTHLY")
                .build()));

        UserUsageOverviewResponse response = service.getCurrentUserOverview("josh");

        assertThat(response.getBalance().getSubscriptionAvailableCreditsMicros()).isEqualTo(400_000L);
        assertThat(response.getBalance().getPurchasedAvailableCreditsMicros()).isEqualTo(100_000L);
        assertThat(response.getBalance().getTotalCreditsMicros()).isEqualTo(500_000L);
        assertThat(response.getBalance().getSpendableCreditsMicros()).isEqualTo(500_000L);
    }

    @Test
    void getCurrentUserOverview_NegativePurchasedBalance_ExposesNegativeTotalAndZeroSpendable() {
        stubUser();
        when(creditWalletRepository.findByUserId(101L)).thenReturn(Optional.of(CreditWallet.builder()
                .userId(101L)
                .purchasedBalanceMicros(-20_000L)
                .build()));
        when(billingEventRepository.sumNetPurchasedCreditsMicros(101L)).thenReturn(100_000L);
        when(llmUsageCreditLedgerRepository.sumPurchasedAllocatedCreditsMicros(101L)).thenReturn(120_000L);
        when(subscriptionContractRepository.findOpenContractByUserId(101L)).thenReturn(Optional.empty());

        UserUsageOverviewResponse response = service.getCurrentUserOverview("josh");

        assertThat(response.getBalance().getTotalCreditsMicros()).isEqualTo(-20_000L);
        assertThat(response.getBalance().getSpendableCreditsMicros()).isZero();
        assertThat(response.getBalance().getPurchasedTotalCreditsMicros()).isEqualTo(100_000L);
        assertThat(response.getBalance().getPurchasedUsedCreditsMicros()).isEqualTo(120_000L);
        assertThat(response.getBalance().getPurchasedAvailableCreditsMicros()).isZero();
        assertThat(response.getBalance().getRawPurchasedBalanceMicros()).isEqualTo(-20_000L);
        assertThat(response.getBalance().isNegative()).isTrue();
    }

    private void stubUser() {
        when(userRepository.findByUsername("josh")).thenReturn(Optional.of(User.builder()
                .id(101L)
                .externalId(UUID.randomUUID())
                .username("josh")
                .email("josh@example.com")
                .password("hashed")
                .build()));
    }

    private SubscriptionContract activeContract() {
        return SubscriptionContract.builder()
                .id(21L)
                .userId(101L)
                .billingPlanId(31L)
                .billingPlanVersionId(41L)
                .status(SubscriptionContractStatus.ACTIVE)
                .currentPeriodStart(LocalDateTime.of(2026, 4, 1, 0, 0))
                .currentPeriodEnd(LocalDateTime.of(2026, 5, 1, 0, 0))
                .build();
    }
}
