package com.josh.interviewj.usage.service;

import com.josh.interviewj.auth.model.User;
import com.josh.interviewj.auth.repository.UserRepository;
import com.josh.interviewj.billing.model.BillingPlan;
import com.josh.interviewj.billing.model.BillingPlanVersion;
import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;
import com.josh.interviewj.billing.repository.BillingPlanRepository;
import com.josh.interviewj.billing.repository.BillingPlanVersionRepository;
import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import com.josh.interviewj.usage.dto.response.UserUsageOverviewResponse;
import com.josh.interviewj.usage.model.UsageHistoryWindowType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserUsageOverviewQueryService {

    private final Clock clock;
    private final UserRepository userRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingPlanVersionRepository billingPlanVersionRepository;
    private final CreditsBalanceSnapshotService creditsBalanceSnapshotService;
    private final CreditFormattingService creditFormattingService;

    public UserUsageOverviewResponse getCurrentUserOverview(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_003, "User not found"));

        CreditsBalanceSnapshot snapshot = creditsBalanceSnapshotService.getSnapshot(user.getId());
        LocalDateTime now = nowUtc();
        SubscriptionContract contract = snapshot.openContract();
        BillingPlan plan = contract == null ? null : billingPlanRepository.findById(contract.getBillingPlanId()).orElse(null);
        BillingPlanVersion version = contract == null ? null : billingPlanVersionRepository.findById(contract.getBillingPlanVersionId()).orElse(null);

        return UserUsageOverviewResponse.builder()
                .snapshotAt(toOffset(now))
                .defaultWindow(defaultWindow(snapshot, now))
                .balance(balance(snapshot))
                .subscription(subscription(snapshot, plan, version))
                .build();
    }

    private UserUsageOverviewResponse.DefaultWindow defaultWindow(CreditsBalanceSnapshot snapshot, LocalDateTime now) {
        if (snapshot.hasActiveSubscription()) {
            return UserUsageOverviewResponse.DefaultWindow.builder()
                    .windowType(UsageHistoryWindowType.CURRENT_SUBSCRIPTION_PERIOD)
                    .from(toOffset(snapshot.openContract().getCurrentPeriodStart()))
                    .to(toOffset(snapshot.openContract().getCurrentPeriodEnd()))
                    .build();
        }
        return UserUsageOverviewResponse.DefaultWindow.builder()
                .windowType(UsageHistoryWindowType.LAST_30_DAYS)
                .from(toOffset(now.minusDays(30)))
                .to(toOffset(now))
                .build();
    }

    private UserUsageOverviewResponse.Balance balance(CreditsBalanceSnapshot snapshot) {
        return UserUsageOverviewResponse.Balance.builder()
                .totalCreditsMicros(snapshot.totalCreditsMicros())
                .totalCredits(creditFormattingService.formatCreditsMicros(snapshot.totalCreditsMicros()))
                .spendableCreditsMicros(snapshot.spendableCreditsMicros())
                .spendableCredits(creditFormattingService.formatCreditsMicros(snapshot.spendableCreditsMicros()))
                .purchasedAvailableCreditsMicros(snapshot.purchasedAvailableCreditsMicros())
                .purchasedAvailableCredits(creditFormattingService.formatCreditsMicros(snapshot.purchasedAvailableCreditsMicros()))
                .purchasedTotalCreditsMicros(snapshot.purchasedTotalCreditsMicros())
                .purchasedTotalCredits(creditFormattingService.formatCreditsMicros(snapshot.purchasedTotalCreditsMicros()))
                .purchasedUsedCreditsMicros(snapshot.purchasedUsedCreditsMicros())
                .purchasedUsedCredits(creditFormattingService.formatCreditsMicros(snapshot.purchasedUsedCreditsMicros()))
                .rawPurchasedBalanceMicros(snapshot.rawPurchasedBalanceMicros())
                .rawPurchasedBalance(creditFormattingService.formatCreditsMicros(snapshot.rawPurchasedBalanceMicros()))
                .subscriptionAvailableCreditsMicros(snapshot.subscriptionAvailableCreditsMicros())
                .subscriptionAvailableCredits(creditFormattingService.formatCreditsMicros(snapshot.subscriptionAvailableCreditsMicros()))
                .hasAnyCredits(snapshot.hasAnyCredits())
                .isNegative(snapshot.isNegative())
                .build();
    }

    private UserUsageOverviewResponse.Subscription subscription(
            CreditsBalanceSnapshot snapshot,
            BillingPlan plan,
            BillingPlanVersion version
    ) {
        boolean active = snapshot.hasActiveSubscription();
        List<UserUsageOverviewResponse.Bucket> buckets = active
                ? snapshot.activeGrants().stream().map(this::toBucket).toList()
                : List.of();
        return UserUsageOverviewResponse.Subscription.builder()
                .active(active)
                .status(active ? snapshot.openContract().getStatus().name() : null)
                .planCode(active && plan != null ? plan.getPlanCode() : null)
                .tierCode(active && plan != null ? plan.getTierCode() : null)
                .currentBillingCycle(active && version != null ? version.getBillingCycle() : null)
                .currentPeriodStart(active ? toOffset(snapshot.openContract().getCurrentPeriodStart()) : null)
                .currentPeriodEnd(active ? toOffset(snapshot.openContract().getCurrentPeriodEnd()) : null)
                .bucketCount(buckets.size())
                .buckets(buckets)
                .build();
    }

    private UserUsageOverviewResponse.Bucket toBucket(SubscriptionQuotaGrant grant) {
        long remainingAmountMicros = Math.max(
                grant.getGrantedAmountMicros() - grant.getUsedAmountMicros() - grant.getExpiredAmountMicros(),
                0L
        );
        return UserUsageOverviewResponse.Bucket.builder()
                .bucketCode(grant.getBucketCode())
                .grantedAmountMicros(grant.getGrantedAmountMicros())
                .usedAmountMicros(grant.getUsedAmountMicros())
                .remainingAmountMicros(remainingAmountMicros)
                .grantedAmount(creditFormattingService.formatCreditsMicros(grant.getGrantedAmountMicros()))
                .usedAmount(creditFormattingService.formatCreditsMicros(grant.getUsedAmountMicros()))
                .remainingAmount(creditFormattingService.formatCreditsMicros(remainingAmountMicros))
                .periodStart(toOffset(grant.getPeriodStart()))
                .periodEnd(toOffset(grant.getPeriodEnd()))
                .build();
    }

    private OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
