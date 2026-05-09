package com.josh.interviewj.usage.service;

import com.josh.interviewj.billing.model.SubscriptionContract;
import com.josh.interviewj.billing.model.SubscriptionQuotaGrant;

import java.util.List;

public record CreditsBalanceSnapshot(
        long rawPurchasedBalanceMicros,
        long purchasedAvailableCreditsMicros,
        long purchasedTotalCreditsMicros,
        long purchasedUsedCreditsMicros,
        long subscriptionAvailableCreditsMicros,
        long totalCreditsMicros,
        long spendableCreditsMicros,
        boolean hasAnyCredits,
        boolean isNegative,
        SubscriptionContract openContract,
        List<SubscriptionQuotaGrant> activeGrants
) {

    public boolean hasActiveSubscription() {
        return openContract != null
                && openContract.getCurrentPeriodStart() != null
                && openContract.getCurrentPeriodEnd() != null;
    }
}
